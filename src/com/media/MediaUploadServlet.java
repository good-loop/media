package com.media;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	 * ~525mb
	 */
	long MAX_UPLOAD = 500L * 1024L * 1024L;
	public static final String ACTION_UPLOAD = "upload";
	/**
	 * NB see ConfigBuilder.bytesFromString()
	 * @param mAX_UPLOAD
	 */
	public void setMaxUpload(long maxBytes) {
		this.MAX_UPLOAD = maxBytes;
	}
	
	// TODO: why are these needed? Do I actually need to have multiple of these?
	public static final FileUploadField UPLOAD = new FileUploadField("upload");
	public static final FileUploadField STANDARD_UPLOAD = new FileUploadField("standard_upload");
	public static final FileUploadField MOBILE_UPLOAD = new FileUploadField("mobile_upload");
	public static final FileUploadField RAW_UPLOAD = new FileUploadField("raw_upload");
	
	// Limit number of threads available to servlet at any given time
	static ExecutorService pool = Executors.newFixedThreadPool(10);
	
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
		// Random string appended to upload file name
		// Means that the same uploaded asset will be saved to a different file each time
		File tempFile = state.get(UPLOAD);
		
		// Do the storage!
		Map<String, File> _assetArr = doUpload2(tempFile, name, state.getParameterMap());
		
		File rawFile = _assetArr.get("raw");
		if( rawFile != null) {
			addUploadedAssetToCargoAndState(rawFile, "raw", RAW_UPLOAD, cargo, state);
		}
		
		File standardFile = _assetArr.get("standard");
		if( standardFile != null ) {
			addUploadedAssetToCargoAndState(standardFile, "standard", STANDARD_UPLOAD, cargo, state);
		}
		
		File mobileFile = _assetArr.get("mobile");		
		if( mobileFile != null ) {
			addUploadedAssetToCargoAndState(mobileFile, "mobile", MOBILE_UPLOAD, cargo, state);
		}
		
		return _assetArr;
	}
	
	protected Map addUploadedAssetToCargoAndState(File asset, String label, FileUploadField uploadField, Map cargo, WebRequest state) {
		Map<String, Object> params = new ArrayMap();
		
		params.put("contentSize", asset.length());
		params.put("uploadDate", new Time().toISOString());
		params.put("author", state.getUserId());
		params.put("fileFormat", WebUtils2.getMimeType(asset));
		params.put("name", asset.getName());
		params.put("absolutePath", asset.getAbsolutePath());
		
		String relpath = FileUtils.getRelativePath(asset, webRoot);
		// ugly code to avoid // inside the path whatever the path bits
		if (relpath.startsWith("/")) relpath.substring(1);		
		String url = server +(server.endsWith("/")? "" : "/")+relpath;
		params.put("url", url);
		
		cargo.put(label, params);
		state.put(uploadField, asset);
		
		// all OK
		state.addMessage(Printer.format("File {0} uploaded", asset.getAbsolutePath()));
		return cargo;
	};
	
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
	public Map<String, File> doUpload2(File tempFile, String name, Map params) 
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
		File rawDest = null;
		File standardDest = null;
		File mobileDest = null;
		try {
			// Do the upload
			Log.i("Accepting upload of "+tempFile.length()+" bytes, temp location "+tempFile.getAbsolutePath(), Level.FINE);
			rawDest = getDestFile("raw", tempFile);
			// Moderately compressed version. Generated by either doProcessImage or doProcessVideo
			standardDest = getDestFile("standard", tempFile);
			// Most compressed version. Generated by either doProcessImage or doProcessVideo
			mobileDest = getDestFile("mobile", tempFile);
			
			assert tempFile.exists() : "Destination file doesn't exist: "+tempFile.getAbsolutePath();
			Log.report(tempFile.length()+" bytes uploaded to "+tempFile.getAbsolutePath(), Level.FINE);
			// Move to final resting place
			FileUtils.move(tempFile, rawDest);
			// Map of absolute paths
			Map<String, File> _assetArr = new ArrayMap();
			_assetArr.put("raw", rawDest);
			if (FileUtils.isImage(tempFile)) {
				FileProcessor imageProcessor = FileProcessor.ImageProcessor(rawDest, standardDest, mobileDest);
				_assetArr = imageProcessor.run(pool);
			} else if (FileUtils.isVideo(tempFile)) {
				FileProcessor videoProcessor = FileProcessor.VideoProcessor(rawDest, standardDest, mobileDest);
				_assetArr = videoProcessor.run(pool);
			}
			// done
			return _assetArr;
		
		// Error handling
		} catch (Throwable e) {
			doUpload3_rollback(tempFile, standardDest, mobileDest);
			throw Utils.runtime(e);
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