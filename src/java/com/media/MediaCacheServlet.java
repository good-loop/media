package com.media;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.winterwell.utils.Dep;
import com.winterwell.utils.FailureException;
import com.winterwell.utils.Proc;
import com.winterwell.utils.Utils;
import com.winterwell.utils.WrappedException;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.time.Dt;
import com.winterwell.utils.time.TUnit;
import com.winterwell.web.FakeBrowser;
import com.winterwell.web.WebEx;
import com.winterwell.web.app.FileServlet;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.WebRequest;
import com.winterwell.web.fields.StringField;

/**
 * Hit /mediacache/[complete URL encoded as base64url + filetype] and if the request
 * doesn't match a previously-cached file, this servlet will catch your request,
 * retrieve the file, and cache it.
 * See counterpart code in adserver/adunit/src/util/utils.js --> function wrapUrl
 * 
 * Request /mediacache/scaled/(h|w)/(number)/[same filename] to get the same file,
 * scaled down (if it's larger) to (h)eight or (w)idth
 * 
 * Requires ImageMagick ("convert") and exiftool installed on the server.
 * 
 * 
 * 
 * TODO Implement better security - eg sign with salted hash of a valid ad ID?
 * @author roscoe
 *
 */
public class MediaCacheServlet implements IServlet {
	private static final String LOGTAG = "MediaCacheServlet";

	/** Pull the numbers out of the "Image Size" line of exiftool's output */
	static Pattern imgSizePattern = Pattern.compile("^Image Size[^\\d]*(\\d+)x(\\d+)", Pattern.MULTILINE);
	
	static Pattern resizePathPattern = Pattern.compile("/scaled/([wh])/(\\d+)");

	/** Track in-progress caching operations so rapid-fire requests don't make a mess */
	static Map<String, Lock> inProgress = new HashMap<String, Lock>();
	
	/** Let's not cache EVERYTHING any rando throws at us. */
	private static List<String> acceptExtensions = Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".svg", ".mp4", ".mpeg4", ".m4v", ".webm", ".webp");

	private static StringField SRC = new StringField("src");

	// Don't cache more than 10mb? TODO Think about this for video purposes...
	private long maxSize = 10000000L;

	File webRoot = new File("web");
	
	private File cacheRoot;

	public MediaCacheServlet() {
		File uploadDir;
		MediaConfig mc = Dep.getWithDefault(MediaConfig.class, null);
		if (mc!= null && mc.uploadDir!=null) {
			uploadDir = mc.uploadDir;
		} else {
			uploadDir = new File("web/uploads");
		}
		cacheRoot = new File(uploadDir, "mediacache");
		if ( ! cacheRoot.exists()) {
			boolean ok = cacheRoot.mkdirs();
			if (!ok) throw new FailureException("Could not create directory " + cacheRoot);
		}		
	}
	
	private Base64.Decoder decoder = Base64.getUrlDecoder();
	
	@Override
	public void process(WebRequest state) throws IOException {
		URL reqUrl = new URL(state.getRequestUrl());
		
		// Strip off leading path components to get filename (we'll be storing to this)
		String filename = reqUrl.getPath().replaceAll("^.*\\/", "");
		
		// Is someone trying to cache something they shouldn't?
		String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase();
		if (!acceptExtensions.contains(extension)) {
			throw new WebEx.E403("We don't cache this file type: " + extension);
		}
		
		// The request may come with a param "&src=https://www.domain.com/filename.png"
		// If so: fetch that URL & store it at the requested filename.
		// Per our convention the filename should be a digest of the source URL.
		// Collisions aren't important, security is.
		String srcUrl = state.get(SRC);
		if (Utils.isBlank(srcUrl)) {
			// If not: Assume the filename (minus extension) is a base64-web encoded URL & decode it.
			// TODO Remove ".gl-size-xxx" wart
			String srcUrlEncoded = filename.replaceAll("\\..*$", "");
			srcUrl = new String(decoder.decode(srcUrlEncoded));
		}

		// ...security: some safety checks on the path against hackers (should we whitelist allowed characters with a regex instead??)
		String path = state.getRequestPath();
		if (!FileUtils.isSafe(path)) {
			throw new WebEx.E403(reqUrl.toString(), "Blocked unsafe characters (path: "+path+")");
		}		
		// ...extra security check (see Aug 2023 bug)
		if (!FileUtils.isSafe(filename)) { // ??paranoia given the path check
			throw new WebEx.E403("Bad filename: "+srcUrl);
		}
		if (!FileUtils.isSafe(srcUrl)) {
			throw new WebEx.E403("Bad src path: "+srcUrl);
		}
		
		// Once we have the file, we'll hand off to FileServlet - so keep tabs on its location outside the fetch/wait block
		File toServe = new File(cacheRoot.getAbsolutePath(), filename);
		
		// Don't let other threads try and cache the same file if rapid-fire requests come in!
		if (!inProgress.containsKey(srcUrl)) {
			Lock running = null;
			try {
				running = new ReentrantLock();
				inProgress.put(srcUrl, running);
				running.lock();
				// Could be we already downloaded the base version and this is a resize request - skip downloading if so.
				if (!toServe.exists()) {
					// Handle on where we want the raw copy of the file to be...
					toServe = FileUtils.getNewFile(new File(cacheRoot.getAbsolutePath(), filename));
					
					URL srcUrlObj = new URL(srcUrl);
					if (srcUrlObj.getHost().equals(reqUrl.getHost()) 
							&& srcUrlObj.getPath().startsWith("/uploads")) 
					{
						File existingFileInDifferentPlace = new File(webRoot, srcUrlObj.getPath());
						try {
							// If the file is on this uploads server, create a symlink to it in the requested location.
							// So that next time nginx can handle this
							symlink(toServe, existingFileInDifferentPlace);
						} catch (Throwable ex) {
							Log.e(LOGTAG, "swallow symlink "+ex+" for "+state);
							toServe = existingFileInDifferentPlace; // oh well -- serve from where it is
						}
					} else {
						// If it's on another host, fetch and save it.
						fetch(srcUrlObj, toServe);
					}
				} // ./!toServe.exists
				// Does the path contain an implicit resize request?
				toServe = maybeResize(path, toServe);
			} catch (IllegalArgumentException e) {
				// This will be a "requested unsafe path" exception - 403 Forbidden
				throw new WebEx.E403(reqUrl.toString(), e.getMessage());
			} catch (Exception e) {
				// Something unexpected went wrong
				throw new WebEx.E50X(e);
			} finally {
				// Tell any other threads looking for this file that it's ready
				if (running != null) running.unlock();
				inProgress.remove(srcUrl);
			}
		} else {
			// Another thread is already caching the file, wait for it to finish
			Lock waitFor = inProgress.get(srcUrl);
			waitFor.lock(); // blocks until this lock is available
			waitFor.unlock(); // free it immediately
		}
		try {
			FileServlet.serveFile(toServe, state);
		} catch (WrappedException | EOFException ex) {
			Log.i(LOGTAG, ex+" for "+state); // most likely the remote browser disconnected
		}
	}


	/**
	 * Get a resource from a remote server and save it locally.
	 * @param source
	 * @param target
	 * @throws IOException
	 * @throws WebEx.E403
	 */
	private void fetch(URL source, File target) throws IOException, WebEx.E403 {
		// Get response headers and check the file size before we load the whole file
		HttpURLConnection conn = (HttpURLConnection) source.openConnection();
		long size = Integer.parseInt(conn.getHeaderField("Content-Length"));
		if (size > maxSize) {
			throw new WebEx.E403("File too big to cache: " + (size / 1024) + "KB");
		}
		conn.disconnect();
				
		// Fetch the file. Could reuse the connection we used to check size but FakeBrowser skips a LOT of boilerplate
		FakeBrowser fb = new FakeBrowser();
		File tmpFile = fb.getFile(source.toString());
		
		// Move the file to the location it was originally requested from, so future calls will be a file hit
		FileUtils.move(tmpFile, target);
		fetch2_stripMetaData(target);
	}


	private void fetch2_stripMetaData(File target) {
		// ...and strip metadata
		try {
			exif(target, true);
		} catch (Exception e) {
			if (e.toString().contains("Cannot run program")) {
				// Missing exiftool? Log and continue (exif stripping isn't a critical function)
				Log.e("This server does not have exiftool installed, which is necessary for optimal functioning of MediaCacheServlet.", e);
			} else {
				// Any other error? swallow (noisily in logs)
				Log.e(LOGTAG, e);
			}
		}
	}
	
	/**
	 * Run command exiftool on a file.
	 * @param target The file to examine/process
	 * @param stripMetadata True to attempt to remove all metadata.
	 * @return exiftool's terminal output
	 */
	private String exif(File target, boolean stripMetadata) {
		String path = target.getAbsolutePath();
		
		// A check is already done in process() - but just in case anything else ever calls this method
		if (!FileUtils.isSafe(path)) {
			throw new WebEx.E403("Unsafe characters (path: "+path+")");
		}
		
		String cmd = "exiftool " + (stripMetadata ? "-all= " : "") + path;
		Proc proc = new Proc(cmd);
		proc.start();
		proc.waitFor(new Dt(20, TUnit.SECOND));
		String output = proc.getOutput();
		proc.close();
		return output;
	}


	/**
	 * Create a relative symlink - eg so "uploads/mediacache/xxxxx.png" can point to "../standard/xxxxx.png"
	 * @param linkLocation The filename where the link should be created
	 * @param sourceFile The file the link should point to
	 * @throws IOException
	 */
	private void symlink(File linkLocation, File sourceFile) throws IOException {
		 // Already done - eg propagated across GlusterFS by MediaCacheServlet on another server? Silently continue.
		if (linkLocation.exists()) {
			return;
		}
		if ( ! sourceFile.exists()) {
			throw new FileNotFoundException(sourceFile.toString());
		}
		Path link = linkLocation.getAbsoluteFile().toPath(); // abs location to create symlink at
		Path tgt = sourceFile.getCanonicalFile().toPath(); // abs location of link target
		Path relTgt = link.getParent().relativize(tgt);
		Files.createSymbolicLink(link, relTgt);
	}
	
	
	/**
	 * Check if the request path implies the image should be resized, and resize as requested.
	 * @param path The local path the image was requested at (eg "/uploads/mediacache/scaled/w/720/xxxxx.png")
	 * @param original The raw image to be scaled
	 * @throws Exception 
	 */
	private File maybeResize(String path, File original) throws IllegalArgumentException, Exception {
		// Is the request path a well-formed "scale to width/height X" directory?
		Matcher resizePathMatcher = resizePathPattern.matcher(path);
		if (!resizePathMatcher.find()) return original; // No, it's not. No resizing needed.
		
		// Don't want to throw WebEx directly from in here as we don't have access to the request URL to do it properly
		if (!FileUtils.isSafe(path)) {
			throw new IllegalArgumentException("Blocked unsafe characters (path: "+path+")");
		}
		
		// "w" or "h"
		String scaleType = resizePathMatcher.group(1);

		try {
			// Last subdirectory of path should be parseable as an integer for sizing.
			int targetSize = Integer.parseInt(resizePathMatcher.group(2));
			
			// Check if the requested resize would be an upscale
			// Also - don't try to resize SVGs. The browser is much better at it than you.
			boolean doResize = !original.getName().endsWith(".svg");
			if (doResize) {
				// Failure modes:
				// - exiftool not installed: log and continue
				// - exiftool output doesn't match regex: log and continue
				String exifout = ""; // make available in the catch block for debugging
				try {
					exifout = exif(original, false);
					Matcher sizeMatcher = imgSizePattern.matcher(exifout);
					sizeMatcher.find();
					int currentSize = Integer.parseInt(sizeMatcher.group("w".equals(scaleType) ? 1 : 2));
					if (currentSize < targetSize) doResize = false;
				} catch (Exception e) {
					if (e.toString().contains("Cannot run program")) {
						Log.e("This server does not have exiftool installed, which is necessary for optimal functioning of MediaCacheServlet.", e);
					} else if (e instanceof IllegalStateException || e instanceof IndexOutOfBoundsException) {
						Log.e("Tried to extract image dimensions using exiftool "+original+", but output does not match the expected form.", e);
						Log.i(LOGTAG, "Unexpected exiftool output was: \n" + exifout);
					}
				}
			}

			// Ensure the output dir exists			
			File outDir = new File(cacheRoot, path).getParentFile();
			if (!outDir.exists()) outDir.mkdirs();
			File outFile = new File(outDir, original.getName());

			String inFormat = FileUtils.getType(original);
			String outFormat = FileUtils.getType(outFile);
			boolean doConvert = !inFormat.equalsIgnoreCase(outFormat);

			String resizeArg = "";
			String convertArg = "";

			if (doResize) {
				// `convert -resize` accepts dimensions in forms "100x100" (WxH), "100x" (W only), "x100" (H only)
				resizeArg = " -resize " + ("w".equals(scaleType) ? (targetSize + "x") : ("x" + targetSize));
			}
			if (doConvert) {
				// default webp conversion can produce absolutely deep-fried images, but high-quality compression still beats jpg
				// see ticket https://good-loop.monday.com/boards/2603585504/views/60487313/pulses/3943458907
				if ("webp".equals(outFormat)) convertArg = " -quality 90";
			}

			if (doResize || doConvert) {
				Proc processImage = new Proc("convert " + original.getAbsolutePath() + resizeArg + convertArg + " " + outFile.getAbsolutePath());
				processImage.start();
				int ok = processImage.waitFor(5000); // 5 seconds... should never happen, but.
				processImage.close();
			} else {
				// Result would be unchanged / scaled larger? Just symlink the original.
				symlink(outFile, original);
			} 
			
			// We'll be serving this newly-created file (or symlink), so return it.
			return outFile;
		} catch (Exception e) {
			// Possible exceptions:
			// - InterruptedException from Proc.waitFor()
			// - What else?
			throw e;
		}
	}

}