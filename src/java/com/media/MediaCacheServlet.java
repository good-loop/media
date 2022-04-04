package com.media;

import java.io.File;
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

import javax.servlet.http.HttpServletResponse;

import com.winterwell.utils.FailureException;
import com.winterwell.utils.Proc;
import com.winterwell.utils.Utils;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.time.Dt;
import com.winterwell.utils.time.TUnit;
import com.winterwell.utils.time.Time;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.FakeBrowser;
import com.winterwell.web.WebEx;
import com.winterwell.web.WebInputException;
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
	private static List<String> acceptExtensions = Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".svg", ".mp4", ".mpeg4", ".m4v", ".webm");

	private static StringField SRC = new StringField("src");

	// Don't cache more than 10mb? TODO Think about this for video purposes...
	private long maxSize = 10000000L;
	
	private File webRoot = new File("web/");
	
	private File cacheRoot = new File(webRoot, "uploads/mediacache/");
	{
		if (!cacheRoot.exists()) {
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
		
		// ...some safety checks on the path against hackers (should we whitelist allowed characters with a regex instead??)
		String path = state.getRequestPath();
		if (!FileUtils.isSafe(path)) {
			throw new WebEx.E403(reqUrl.toString(), "Blocked unsafe characters (path: "+path+")");
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

		// Once we have the file, we'll hand off to FileServlet - so keep tabs on its location outside the fetch/wait block
		File toServe = new File(cacheRoot.getAbsolutePath(), filename);
		
		// Don't let other threads try and cache the same file if rapid-fire requests come in!
		if (!inProgress.containsKey(srcUrl)) {
			Lock running = new ReentrantLock();
			inProgress.put(srcUrl, running);
			running.lock();
			
			// Could be we already downloaded the base version and this is a resize request - skip downloading if so.
			if (!toServe.exists()) {
				// Handle on where we want the raw copy of the file to be...
				toServe = FileUtils.getNewFile(new File(cacheRoot.getAbsolutePath(), filename));
				
				URL srcUrlObj = new URL(srcUrl);
				if (srcUrlObj.getHost().equals(reqUrl.getHost()) && srcUrlObj.getPath().startsWith("/uploads")) {
					// If the file is on this uploads server, create a symlink to it in the requested location.
					symlink(toServe, new File(webRoot, srcUrlObj.getPath()));
				} else {
					// If it's on another host, fetch and save it.
					fetch(srcUrlObj, toServe);
				}
			}
			
			// Does the path contain an implicit resize request?
			try {
				toServe = maybeResize(path, toServe);
			} catch (Exception e) {
				throw new WebEx.E50X(e);
			} finally {
				// Tell any other threads looking for this file that it's ready
				running.unlock();
				inProgress.remove(srcUrl);
			}
		} else {
			// Another thread is already caching the file, wait for it to finish
			Lock waitFor = inProgress.get(srcUrl);
			waitFor.lock(); // blocks until this lock is available
			waitFor.unlock();
		}
		
		FileServlet.serveFile(toServe, state);
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
	 * @param targetFile The file the link should point to
	 * @throws IOException
	 */
	private void symlink(File linkLocation, File targetFile) throws IOException {
		 // Already done - eg propagated across GlusterFS by MediaCacheServlet on another server? Silently continue.
		if (targetFile.exists()) return; 
		Path link = linkLocation.getAbsoluteFile().toPath(); // abs location to create symlink at
		Path tgt = targetFile.getCanonicalFile().toPath(); // abs location of link target
		Path relTgt = link.getParent().relativize(tgt);
		Files.createSymbolicLink(link, relTgt);
	}
	
	
	/**
	 * Check if the request path implies the image should be resized, and resize as requested.
	 * @param path The local path the image was requested at (eg "/uploads/mediacache/scaled/w/720/xxxxx.png")
	 * @param original The raw image to be scaled
	 * @throws Exception 
	 */
	private File maybeResize(String path, File original) throws Exception {
		// Is the request path a well-formed "scale to width/height X" directory?
		Matcher resizePathMatcher = resizePathPattern.matcher(path);
		if (!resizePathMatcher.find()) return original; // No, it's not. No resizing needed.
		
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
				try {
					String exifout = exif(original, false);
					Matcher sizeMatcher = imgSizePattern.matcher(exifout);
					sizeMatcher.find();
					int currentSize = Integer.parseInt(sizeMatcher.group("w".equals(scaleType) ? 1 : 2));
					if (currentSize < targetSize) doResize = false;
				} catch (Exception e) {
					if (e.toString().contains("Cannot run program")) {
						Log.e("This server does not have exiftool installed, which is necessary for optimal functioning of MediaCacheServlet.", e);
					} else if (e instanceof IllegalStateException || e instanceof IndexOutOfBoundsException) {
						Log.e("Tried to extract image dimensions using exiftool "+original+", but output does not match the expected form.", e);
					}
				}
			}

			// Ensure the output dir exists			
			File outDir = new File(cacheRoot, FileUtils.safeFilename(path)).getParentFile();
			if (!outDir.exists()) outDir.mkdirs();
			File outFile = new File(outDir, FileUtils.safeFilename(original.getName()));
			
			if (doResize) {
				// `convert -resize` accepts dimensions in forms "100x100" (WxH), "100x" (W only), "x100" (H only)
				String resizeArg = "w".equals(scaleType) ? (targetSize + "x") : ("x" + targetSize);
				Proc resize = new Proc("convert " + original.getAbsolutePath() + " -resize " + resizeArg + " " + outFile.getAbsolutePath());
				resize.start();
				int ok = resize.waitFor(5000); // 5 seconds... should never happen, but.
				resize.close();
			} else {
				// Requested size is larger? Symlink the original instead of upscaling needlessly
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