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
	static String LOW_RES_QUALITY = "360px";
	/**
	 * NB see ConfigBuilder.bytesFromString()
	 * @param mAX_UPLOAD
	 */
	public void setMaxUpload(long maxBytes) {
		this.MAX_UPLOAD = maxBytes;
	}
	
	public static final FileUploadField UPLOAD = new FileUploadField("upload");
	public static final FileUploadField STANDARD_UPLOAD = new FileUploadField("standard_upload");
	public static final FileUploadField MOBILE_UPLOAD = new FileUploadField("mobile_upload");
	
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
	private Map<String, File> doUpload(WebRequest state, Map cargo) throws WebInputException {
		if (cargo==null) cargo = new ArrayMap(); // avoid NPEs
		state.processMultipartIncoming(new ArrayMap<String, AField>());
		// name from original filename - accessed via the pseudo field made by FileUploadField
		String name = state.get(new Key<String>(UPLOAD.getFilenameField()), "");
		// Get the file
		File tempFile = state.get(UPLOAD);
		
		// Do the storage!
		Map<String, File> _assetArr = doUpload2(tempFile, name, cargo, state.getParameterMap());
		
		File standardFile = _assetArr.get("standard");
		if( standardFile != null ) {
			Map<String, Object> standardParams = new ArrayMap();
			
			// TODO: _asset will just be overridden. Add map/array and make sure front-end can deal with this
			// respond
			standardParams.put("contentSize", standardFile.length());
			standardParams.put("uploadDate", new Time().toISOString());
			standardParams.put("author", state.getUserId());
			standardParams.put("fileFormat", WebUtils2.getMimeType(standardFile));
			standardParams.put("name", standardFile.getName());
			standardParams.put("absolutePath", standardFile.getAbsolutePath());
			
			String relpath = FileUtils.getRelativePath(standardFile, webRoot);
			// ugly code to avoid // inside the path whatever the path bits
			if (relpath.startsWith("/")) relpath.substring(1);		
			String url = server +(server.endsWith("/")? "" : "/")+relpath;
			standardParams.put("url", url);
			
			cargo.put("standard", standardParams);
			state.put(STANDARD_UPLOAD, standardFile);
			
			// all OK
			state.addMessage(Printer.format("File {0} uploaded", standardFile.getAbsolutePath()));	
		}
		
		File mobileFile = _assetArr.get("mobile");		
		if( mobileFile != null ) {
			Map<String, Object> mobileParams = new ArrayMap();
			
			mobileParams.put("contentSize", mobileFile.length());
			mobileParams.put("uploadDate", new Time().toISOString());
			mobileParams.put("author", state.getUserId());
			mobileParams.put("fileFormat", WebUtils2.getMimeType(mobileFile));
			mobileParams.put("name", mobileFile.getName());
			mobileParams.put("absolutePath", mobileFile.getAbsolutePath());
			
			String relpath = FileUtils.getRelativePath(mobileFile, webRoot);
			// ugly code to avoid // inside the path whatever the path bits
			if (relpath.startsWith("/")) relpath.substring(1);		
			String url = server +(server.endsWith("/")? "" : "/")+relpath;
			mobileParams.put("url", url);
			
			cargo.put("mobile", mobileParams);
			state.put(MOBILE_UPLOAD, mobileFile);
			
			// all OK
			state.addMessage(Printer.format("File {0} uploaded", mobileFile.getAbsolutePath()));	
		}
		
		return _assetArr;
	}
	
	/**
	 * 
	 * @param server e.g. "https://as.good-loop.com" NB: No trailing /
	 */
	public void setServer(String server) {
		this.server = server;
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
	public Map<String, File> doUpload2(File tempFile, String name, Map cargo, Map params) 
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
		File standardDest = null;
		File mobileDest = null;
		try {
			// Do the upload
			Log.i("Accepting upload of "+tempFile.length()+" bytes, temp location "+tempFile.getAbsolutePath(), Level.FINE);
			standardDest = getDestFile("standard", tempFile);
			// Mobile file will be generated by either doProcessImage or doProcessVideo
			mobileDest = getDestFile("mobile", tempFile);
			
			assert tempFile.exists() : "Destination file doesn't exist: "+tempFile.getAbsolutePath();
			Log.report(tempFile.length()+" bytes uploaded to "+tempFile.getAbsolutePath(), Level.FINE);
			Map _assetArr = new ArrayMap();
			if (FileUtils.isImage(tempFile)) {
				// Standard file is just unmodified upload file.
				// Move in to final place
				FileUtils.move(tempFile, standardDest);
				_assetArr = doProcessImage(standardDest, mobileDest);
			} else if (FileUtils.isVideo(tempFile)) {
				_assetArr = doProcessVideo(tempFile, standardDest, mobileDest);
			}
			// done
			return _assetArr;
		
		// Error handling
		} catch (Throwable e) {
			doUpload3_rollback(tempFile, standardDest, mobileDest);
			throw Utils.runtime(e);
		}
	}
	
	/** User can specify a number of image post-processing options via URL params **/
	protected Map<String, File> doProcessImage(File standardDest, File mobileDest) {			
		String imagePath = standardDest.getAbsolutePath();
		String lowResImagePath = mobileDest.getAbsolutePath();

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
		
		Map out = new ArrayMap();
		out.put("standard", imagePath);
		out.put("mobile", lowResImagePath);
		return out;
	}
	
	/** Source needs to be in different location to outputs: can't write to file you're reading from **/
	protected Map<String, File> doProcessVideo(File tempFile, File standardDest, File mobileDest) {
		String inputVideoPath = tempFile.getAbsolutePath();
		String lowResVideoPath = mobileDest.getAbsolutePath();
		String highResVideoPath = standardDest.getAbsolutePath();
		
		String commands = "";
		
		commands += "HandBrakeCLI " + "--input " + inputVideoPath + " --preset 'Gmail Medium 5 Minutes 480p30' " + "--output " + lowResVideoPath; 
		commands += "; " + "HandBrakeCLI " + "--input " + inputVideoPath + " --preset 'Gmail Large 3 Minutes 720p30' " + "--output " + highResVideoPath;
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
		// Will return before above thread has actually finished executing
		Map out = new ArrayMap();
		out.put("standard", new File(highResVideoPath));
		out.put("mobile", new File(lowResVideoPath));
		return out;
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
	public File getDestFile(String type, File tempFile) {
		File destDir = new File(uploadsDir, type);
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
	private void doUpload3_rollback(File tempFile, File standardDest, File mobileDest) {
		try {
			FileUtils.delete(tempFile);
			FileUtils.delete(standardDest);
			FileUtils.delete(mobileDest);
		} catch (Exception e) {
			// swallow since we're already in a fail path
			Log.report(e);
		}
	}
	
	@Override
	public void process(WebRequest state) throws IOException {
		// ...upload size
		MediaConfig conf = Dep.get(MediaConfig.class);				
		long maxUpload = ConfigBuilder.bytesFromString(conf.maxVideoUpload);
		this.setMaxUpload(maxUpload);

		if (conf.uploadDir!=null) {
			this.setUploadDir(conf.uploadDir);
			this.setWebRoot(new File("web"));		
			KServerType serverType = AppUtils.getServerType(state);
			Log.d(AppUtils.getServerUrl(serverType, "media.good-loop.com").toString());
			this.setServer(AppUtils.getServerUrl(serverType, "media.good-loop.com").toString());
		}
		
		// must be logged in
		state.processMultipartIncoming(new ArrayMap());
		YouAgainClient ya = Dep.get(YouAgainClient.class);
		List<AuthToken> tokens = ya.getAuthTokens(state);		
		if (state.getUser() == null) throw new NoAuthException(state);
		if (ServletFileUpload.isMultipartContent(state.getRequest())) {
	//		try {
			Map cargo = new ArrayMap();			
			Map<String, File> asset = doUpload(state, cargo);
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