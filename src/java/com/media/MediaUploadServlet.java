package com.media;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;

import com.media.data.MediaObject;
import com.winterwell.utils.Dep;
import com.winterwell.utils.FailureException;
import com.winterwell.utils.Key;
import com.winterwell.utils.Printer;
import com.winterwell.utils.StrUtils;
import com.winterwell.utils.TodoException;
import com.winterwell.utils.Utils;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.time.Dt;
import com.winterwell.utils.time.Time;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.FileTooLargeException;
import com.winterwell.web.WebInputException;
import com.winterwell.web.ajax.JsonResponse;
import com.winterwell.web.app.AppUtils;
import com.winterwell.web.app.FileServlet;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.KServerType;
import com.winterwell.web.app.WebRequest;
import com.winterwell.web.fields.AField;
import com.winterwell.web.fields.BoolField;
import com.winterwell.web.fields.FileUploadField;
import com.winterwell.web.fields.MissingFieldException;
import com.winterwell.web.fields.SField;
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
	 * @param maxBytes
	 */
	public void setMaxUpload(long maxBytes) {
		this.MAX_UPLOAD = maxBytes;
	}
	
	// TODO: why are these needed? Do I actually need to have multiple of these?
	public static final FileUploadField UPLOAD = new FileUploadField("upload");
	public static final FileUploadField STANDARD_UPLOAD = new FileUploadField("standard_upload");
	public static final FileUploadField MOBILE_UPLOAD = new FileUploadField("mobile_upload");
	public static final FileUploadField RAW_UPLOAD = new FileUploadField("raw_upload");
	public static final SField MAIN_FILE = new SField("main_file");
	public static final SField UPDATE_REFS = new SField("update_refs");
	private static final String LOGTAG = "MediaUploadServlet";
	
	// Limit number of threads available to servlet at any given time
	static ExecutorService pool = Executors.newFixedThreadPool(10);
	
	
	private final MediaConfig conf;
	private File webRoot;
	
	public MediaUploadServlet() {
		conf = Dep.get(MediaConfig.class);
		webRoot = new File("web");
		if (conf.uploadDir!=null) {
			uploadsDir = conf.uploadDir;
		} else {
			uploadsDir = new File(webRoot, "uploads");
		}
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
	private Map<String, MediaObject> doUpload(WebRequest state, Map cargo) throws WebInputException {
		if (cargo==null) cargo = new ArrayMap(); // avoid NPEs
		state.processMultipartIncoming(new ArrayMap<String, AField>());
		// name from original filename - accessed via the pseudo field made by FileUploadField
		String name = state.get(new Key<String>(UPLOAD.getFilenameField()), "");
		Log.d(LOGTAG, "doUpload name:"+name);
		// Get the file
		// Random string appended to upload file name??
		// Means that the same uploaded asset will be saved to a different file each time
		File tempFile = state.get(UPLOAD);
		
		// Do the storage!
		Map<String, MediaObject> _assetArr = doUpload2(tempFile, name, state.getParameterMap());
		
		ReferenceUpdater refs = new ReferenceUpdater();
		
		Dt rawDuration = null;
		MediaObject rawFile = _assetArr.get("raw");
		if (rawFile != null) {
			// Will just be null if the file is not a video
			rawDuration = rawFile.calculateDuration();
			rawFile.duration = rawDuration;
			addUploadedAssetToCargoAndState(rawFile, "raw", RAW_UPLOAD, cargo, state, refs);
		}
		
		// Font uploads produce many more files than img/video uploads
		if (FileUtils.isFont(tempFile) || FileUtils.isArchive(tempFile)) {
			for (Map.Entry<String, MediaObject> entry : _assetArr.entrySet()) {
				String field = entry.getKey();
				MediaObject mo = entry.getValue();
				if (mo == null) continue;
				addUploadedAssetToCargoAndState(mo, field, new FileUploadField(field), cargo, state, refs);
			}
		} else {
			MediaObject standardFile = _assetArr.get("standard");
			if (standardFile != null) {
				// Seems fair to assume that the duration will not have changed based on processing options
				if (standardFile.duration == null ) standardFile.duration = rawDuration;
				addUploadedAssetToCargoAndState(standardFile, "standard", STANDARD_UPLOAD, cargo, state, refs);
			}

			MediaObject mobileFile = _assetArr.get("mobile");
			if (mobileFile != null) {
				// Seems fair to assume that the duration will not have changed based on processing options
				if( mobileFile.duration == null ) mobileFile.duration = rawDuration;
				addUploadedAssetToCargoAndState(mobileFile, "mobile", MOBILE_UPLOAD, cargo, state, refs);
			}
		}
		
		if (state.getParameterMap().containsKey("update_refs")) {
			Log.i("Replacing file references", Level.FINE);
			Log.i(refs.toString(), Level.FINE);
			for (Map.Entry<String, MediaObject> entry : _assetArr.entrySet()) {
				Log.d("Searching " + entry.getValue().file.getName());
				refs.replaceReferences(entry.getValue().file);
			}
		}

		return _assetArr;
	}


	protected Map addUploadedAssetToCargoAndState(MediaObject assetObject, String label, FileUploadField uploadField, Map cargo, WebRequest state, ReferenceUpdater refs) {
		File asset = assetObject.file;
		Map<String, Object> params = new ArrayMap();

		params.put("contentSize", asset.length());
		params.put("uploadDate", new Time().toISOString());
		params.put("author", state.getUserId());
		params.put("fileFormat", WebUtils2.getMimeType(asset));
		params.put("name", asset.getName());
		params.put("absolutePath", asset.getAbsolutePath());
		
		if( assetObject.duration != null ) {
			// Return duration rounded to nearest integer
			params.put("duration", (int) Math.round(assetObject.duration.getValue()));	
		}

		// Sort out the URL based on our configured base URL and upload folder
		// ugly code to avoid // inside the path whatever the path bits
		String uploadPath = conf.uploadDir.getAbsolutePath();
		if (!uploadPath.endsWith("/")) uploadPath+="/";			// Ensure no leading / in the assetPath
		String absPath = asset.getAbsolutePath();
		assert(absPath.startsWith(uploadPath));					// I have trust issues ...
		String assetPath = absPath.substring(uploadPath.length());
		String url = conf.uploadBaseUrl + (conf.uploadBaseUrl.endsWith("/")?"":"/") + assetPath;
		params.put("url", url);
		
		String preferVersion = state.get(UPDATE_REFS);
		refs.addReferenceUpdate(asset, url, label, preferVersion);
		
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
	public Map<String, MediaObject> doUpload2(File tempFile, String name, Map params) 
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
			rawDest = FileProcessor.getDestFile(FileUtils.getDirectory(uploadsDir, "raw"), tempFile);
			
			assert tempFile.exists() : "Destination file doesn't exist: "+tempFile.getAbsolutePath();
			Log.report(tempFile.length()+" bytes uploaded to "+tempFile.getAbsolutePath(), Level.FINE);
			// Move to final resting place
			FileUtils.move(tempFile, rawDest);
			return FileProcessor.process(pool, uploadsDir, rawDest, null, null, params);
		
		// Error handling
		} catch (Throwable e) {
			// TODO For fonts, rm -rf the created directory
			// Rolback now handled internally in the FileProcessor try/catch
			//doUpload3_rollback(tempFile, standardDest, mobileDest);
			throw Utils.runtime(e);
		}
	}

	private void checkFileSize(File tempFile) {
		if (tempFile.length() > MAX_UPLOAD) { // TODO detect this before uploading a 10gb movie!
			FileUtils.delete(tempFile);
			throw new FileTooLargeException("The file is too large. There is a limit of "+StrUtils.toNSigFigs(MAX_UPLOAD/1100000.0, 2)+"mb on uploads.");
		}
	}
	
	File uploadsDir;
	
	public MediaUploadServlet setUploadDir(File uploadDir) {
		this.uploadsDir = uploadDir;
		return this;
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
	public void process(WebRequest state) throws Exception {
		// ...upload size 	huh??
		if (conf.uploadDir != null) {
			KServerType serverType = AppUtils.getServerType(state);
			Log.d(AppUtils.getServerUrl(serverType, "media.good-loop.com").toString());
			this.setServer(AppUtils.getServerUrl(serverType, "media.good-loop.com").toString());
		}
		
		// Nginx setup bug seen August 2020 - an uploaded file existed, but was not being served 
		// see: https://media.good-loop.com/uploads/captured/rendered.y3c1kLph.mp4 
		// So handle a get here
		if (state.isGET()) {
			FileServlet fs = new FileServlet(webRoot);
			fs.process(state);
			return;
		}
		
		// must be logged in
		state.processMultipartIncoming(new ArrayMap());
		YouAgainClient ya = Dep.get(YouAgainClient.class);
		List<AuthToken> tokens = ya.getAuthTokens(state);
		if (state.getUser() == null) throw new NoAuthException(state);
		if (ServletFileUpload.isMultipartContent(state.getRequest())) {
	//		try {
			Map cargo = new ArrayMap();			
			Map<String, MediaObject> asset = doUpload(state, cargo);
			
			//WebUtils2.CORS(state, true); // forceSet=true
			
			// redirect?
			if (state.sendRedirect()) {
				return;
			}
			
			// loosely based on http://schema.org/MediaObject
			WebUtils2.sendJson(new JsonResponse(state, cargo), state);
			
			return;
	//		} catch(FileTooLargeException ex) {
			// Let the standard code handle it
		}
		throw new TodoException(state.getRequest().getMethod()+" request to media-upload, but no multipart form payload was sent "+state);
	}
}