package com.media;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.apache.commons.fileupload.servlet.ServletFileUpload;

import com.winterwell.utils.Dep;
import com.winterwell.utils.FailureException;
import com.winterwell.utils.Key;
import com.winterwell.utils.Printer;
import com.winterwell.utils.Proc;
import com.winterwell.utils.StrUtils;
import com.winterwell.utils.TodoException;
import com.winterwell.utils.Utils;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.ConfigBuilder;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.time.Time;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.FileTooLargeException;
import com.winterwell.web.WebInputException;
import com.winterwell.web.ajax.JsonResponse;
import com.winterwell.web.app.AppUtils;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.KServerType;
import com.winterwell.web.app.UploadServlet;
import com.winterwell.web.app.WebRequest;
import com.winterwell.web.data.XId;
import com.winterwell.web.fields.AField;
import com.winterwell.web.fields.FileUploadField;
import com.winterwell.web.fields.MissingFieldException;
import com.winterwell.youagain.client.AuthToken;
import com.winterwell.youagain.client.NoAuthException;
import com.winterwell.youagain.client.YouAgainClient;

/** Wraps generic UploadServlet with media.good-loop specific configs **/ 
public class MediaUploadServlet implements IServlet {
	/**
	 * 10mb
	 */
	long MAX_UPLOAD = 100 * 1024L * 1024L;
	public static final String ACTION_UPLOAD = "upload";
	static String LOW_RES_QUALITY = "30%";
	/**
	 * NB see ConfigBuilder.bytesFromString()
	 * @param mAX_UPLOAD
	 */
	public void setMaxUpload(long maxBytes) {
		this.MAX_UPLOAD = maxBytes;
	}
	
	public static final FileUploadField UPLOAD = new FileUploadField("upload");
	
	File webRoot = new File("web"); // = Dep.get(ISiteConfig.class).getWebRootDir();
	
	public void setWebRoot(File webRoot) {
		this.webRoot = webRoot;
	}
	
	
	public MediaUploadServlet() {
	}
	
	String server = "/";
	
	/**
	 * Uploads, renames, (adjusts images), creates and publishes an asset.
	 * Also files it under an object property if set.
	 * 
	 * @param state
	 * @param cargo 
	 * @return asset for the uploaded file
	 * @throws WebInputException
	 */
	private File doUpload(WebRequest state, Map cargo) throws WebInputException {
		if (cargo==null) cargo = new ArrayMap(); // avoid NPEs
		state.processMultipartIncoming(new ArrayMap<String, AField>(
	//		CONVERT.getName(), CONVERT
		));
		// name from original filename - accessed via the pseudo field made by FileUploadField
		String name = state.get(new Key<String>(UPLOAD.getFilenameField()), "");
		// Get the file
		File tempFile = state.get(UPLOAD);
		
		// Do the storage!
		File _asset = doUpload2(state.getUserId(), tempFile, name, cargo, state.getParameterMap());
		
		// respond
		cargo.put("contentSize", _asset.length());
		cargo.put("uploadDate", new Time().toISOString());
		cargo.put("author", state.getUserId());
		cargo.put("fileFormat", WebUtils2.getMimeType(_asset));
		cargo.put("name", _asset.getName());
		cargo.put("absolutePath", _asset.getAbsolutePath());
		
		String relpath = FileUtils.getRelativePath(_asset, webRoot);
		// ugly code to avoid // inside the path whatever the path bits
		if (relpath.startsWith("/")) relpath.substring(1);		
		String url = server +(server.endsWith("/")? "" : "/")+relpath;
		cargo.put("url", url);
		
	
		state.put(UPLOAD, _asset);
		
		// all OK
		state.addMessage(Printer.format("File {0} uploaded", _asset.getName()));
		return _asset;
	}
	
	/**
	 * 
	 * @param server e.g. "https://as.good-loop.com" NB: No trailing /
	 */
	public void setServer(String server) {
		this.server = server;
	}
	
	
	protected File getUploadDir(XId uxid) {
		String udir = FileUtils.safeFilename(uxid==null? "anon" : uxid.toString(), false);		
		return new File(uploadsDir, udir);
	}
	
	/**
	 * Store the file in a new DBAsset
	 * @param tempFile
	 * @param name
	 * @param cargo 
	 * @param user Cannot be null
	 * @param group
	 * @param imageConversion
	 * @param params url parameters
	 * @return
	 */
	public File doUpload2(XId uxid, File tempFile, String name, Map cargo, Map params) 
	{
		if (tempFile==null) {
			throw new MissingFieldException(UPLOAD);
		}
		if (tempFile.length() == 0) {
			throw new WebInputException("Upload failed: no data came through.");
		}
		checkFileSize(tempFile);
		// This is sort of atomic - an error will lead to some cleanup
		// being done
		File dest = null;
		try {
			// Do the upload
			Log.i("Accepting upload of "+tempFile.length()+" bytes, temp location "+tempFile.getAbsolutePath(), Level.FINE);
			dest = getDestFile(uxid, tempFile);
			// Shift it
			FileUtils.move(tempFile, dest);
			assert dest.exists() : "Destination file doesn't exist: "+tempFile.getAbsolutePath();
			Log.report(dest.length()+" bytes uploaded to "+dest.getAbsolutePath(), Level.FINE);
			// Process image based on parameters passed in curl
			// Want to call this after the image has been placed in directory
			if (FileUtils.isImage(dest)) {
				doProcessImage(dest);
			} else if (FileUtils.isVideo(dest)) {
				doProcessVideo(dest);
			}
			// done
			return dest;
		
		// Error handling
		} catch (Throwable e) {
			doUpload3_rollback(tempFile, dest);
			throw Utils.runtime(e);
		}
	}
	
	/** User can specify a number of image post-processing options via URL params **/
	protected void doProcessImage(File dest) {
		// Create new image with same name as normal image but with "lowres_" tacked on to the front
		String imagePath = dest.getAbsolutePath();
		String fileName = dest.getName();
		String lowResImagePath = imagePath.replace(fileName, "lowres_" + fileName);
		// TODO: add some checking of original size/file type
		// Don't want to further reduce tiny files or attempt to scale svgs
		String commands = "";
		// TODO: change this to use specific sizes rather than a random percentage
		commands += "magick " + imagePath + " -resize " + LOW_RES_QUALITY + " " + lowResImagePath;
		
		// Don't want to block server while executing bash script
		if( !commands.isEmpty() ) {
			final String[] threadCommands = new String[] {"/bin/bash", "-c", commands};
			Thread processThread = new Thread(() -> {
				ProcessBuilder process = new ProcessBuilder(threadCommands);
		        // Merge System.err and System.out
				process.redirectErrorStream(true);
		        // Inherit System.out as redirect output stream
				process.redirectOutput(ProcessBuilder.Redirect.INHERIT);
				try {
					process.start();	
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
			processThread.start();
		}
	}
	
	protected void doProcessVideo(File dest) {
		String inputVideoPath = dest.getAbsolutePath();
		String fileName = dest.getName();
		String lowResVideoPath = "";
		String highResVideoPath = "";

		// Replace sequence at end of path of form "-720p" with "-420p"
		if( inputVideoPath.matches("(?<=-)[0-9]{3,4}p(?=\\..{3,4}$)") ) {
			lowResVideoPath = inputVideoPath.replaceFirst("(?<=-)[0-9]{3,4}p(?=\\..{3,4}$)", "-480p");
			highResVideoPath = inputVideoPath.replaceFirst("(?<=-)[0-9]{3,4}p(?=\\..{3,4}$)", "-720p");
		}
		// Insert "-420p" or "-720p" just before the video's file extension
		else {
			lowResVideoPath = inputVideoPath.replaceFirst("^(.*)(?=\\..{3,4}$)", "$1-480p");
			highResVideoPath = inputVideoPath.replaceFirst("^(.*)(?=\\..{3,4}$)", "$1-720p");
		}
		
		String commands = "";
		
		// Commands for video conversion
//		commands += "HandBrakeCLI --input '/home/mark/winterwell/media/web/uploads/markwinterwell.comemail/persil-02-desktop-720p-6113165454459297728.m4v' --preset 'Gmail Medium 5 Minutes 480p30' --output '/home/mark/winterwell/media/web/uploads/markwinterwell.comemail/persil-02-desktop-720p-6113165454459297728-480p.m4v'";
		commands += "HandBrakeCLI " + "--input " + inputVideoPath + " --preset 'Gmail Medium 5 Minutes 480p30' " + "--output " + lowResVideoPath; 
		commands += "; " + "HandBrakeCLI " + "--input " + inputVideoPath + " --preset 'Gmail Large 3 Minutes 720p30' " + "--output " + highResVideoPath;
		commands += "; rm " + inputVideoPath;
		// Execute commands in separate thread
		// Don't want to block server while executing bash script
		if( !commands.isEmpty() ) {
			// Handbrake will only run if you add the /bin/bash -c before command
			// https://stackoverflow.com/questions/44638025/how-to-use-process-builder-in-java-to-run-linux-shell-command
			final String[] threadCommands = new String[] {"/bin/bash", "-c", commands};
			Thread processThread = new Thread(() -> {
				ProcessBuilder process = new ProcessBuilder(threadCommands);
				// Print any errors from process to the console
				process.redirectErrorStream(true);
				process.redirectOutput(ProcessBuilder.Redirect.INHERIT);
				try {
					process.start();	
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
			processThread.start();
		}
	}
	
	private void checkFileSize(File tempFile) {
		if (tempFile.length() > MAX_UPLOAD) { // TODO detect this before uploading a 10gb movie!
			FileUtils.delete(tempFile);
			throw new FileTooLargeException("The file is too large. There is a limit of "+StrUtils.toNSigFigs(MAX_UPLOAD/1100000.0, 2)+"mb on uploads.");
		}
		// is it an image?
	//	if (FileUtils.isImage(tempFile) && tempFile.length() >= Twitter.PHOTO_SIZE_LIMIT) {
	//		FileUtils.delete(tempFile);
	//		throw new FileTooLargeException("The image file is too large. There is a limit of "+StrUtils.toNSigFigs(Twitter.PHOTO_SIZE_LIMIT/1100000.0, 2)+"mb on image uploads.");			
	//	}
	//	// video?
	//	if (FileUtils.isVideo(tempFile) && tempFile.length() >= Twitter.VIDEO_SIZE_LIMIT) {
	//		FileUtils.delete(tempFile);
	//		throw new FileTooLargeException("The video file is too large. There is a limit of "+StrUtils.toNSigFigs(Twitter.VIDEO_SIZE_LIMIT/1100000.0, 2)+"mb on image uploads.");			
	//	}
	}
	
	File uploadsDir = new File("web/uploads");
	
	public MediaUploadServlet setUploadDir(File uploadDir) {
		this.uploadsDir = uploadDir;
		return this;
	}
	
	/**
	 * Does NOT move the tempFile
	 * @param user
	 * @param tempFile
	 * @return suggested dest file
	 */
	public File getDestFile(XId uxid, File tempFile) {
		File destDir = getUploadDir(uxid);
		if ( ! destDir.exists()) {
			boolean ok = destDir.mkdirs();
			if ( ! ok) throw new FailureException("Could not create directory "+destDir);
		}
		File dest = FileUtils.getNewFile(new File(destDir, tempFile.getName()));
		return dest;
	}
	
	/**
	 * Partial clean up of an aborted upload
	 * @param tempFile
	 * @param dest
	 * @param asset
	 */
	private void doUpload3_rollback(File tempFile, File dest) {
		try {
			FileUtils.delete(tempFile);
			FileUtils.delete(dest);
		} catch (Exception e) {
			// swallow since we're already in a fail path
			Log.report(e);
		}
	}
	
	@Override
	public void process(WebRequest state) throws IOException {
		// upload
		UploadServlet servlet = new UploadServlet();
		// ...upload size
		MediaConfig conf = Dep.get(MediaConfig.class);				
		long maxUpload = ConfigBuilder.bytesFromString(conf.maxVideoUpload);
		servlet.setMaxUpload(maxUpload);

		if (conf.uploadDir!=null) {
			servlet.setUploadDir(conf.uploadDir);
			servlet.setWebRoot(new File("web"));		
			KServerType serverType = AppUtils.getServerType(state);
			Log.d(AppUtils.getServerUrl(serverType, "media.good-loop.com").toString());
			servlet.setServer(AppUtils.getServerUrl(serverType, "media.good-loop.com").toString());
		}
		
		// must be logged in
		state.processMultipartIncoming(new ArrayMap());
		YouAgainClient ya = Dep.get(YouAgainClient.class);
		List<AuthToken> tokens = ya.getAuthTokens(state);		
		if (state.getUser() == null) throw new NoAuthException(state);
		if (ServletFileUpload.isMultipartContent(state.getRequest())) {
	//		try {
			Map cargo = new ArrayMap();			
			File asset = doUpload(state, cargo);
			state.sendRedirect();
			
			WebUtils2.CORS(state, false);
			// loosely based on http://schema.org/MediaObject
			WebUtils2.sendJson(new JsonResponse(state, cargo), state);
			
			return;
	//		} catch(FileTooLargeException ex) {
			// Let the standard code handle it
		}
		throw new TodoException(state);
	}
}