package com.media;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.winterwell.utils.FailureException;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.FakeBrowser;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.WebRequest;

/**
 * Hit /mediacache/[complete URL encoded as base64url + filetype] and if the request
 * doesn't match a previously-cached file, this servlet will catch your request,
 * retrieve the file, and cache it.
 * See counterpart code in adserver/adunit/src/util/utils.js --> function wrapUrl
 * TODO Implement better security - eg sign with salted hash of a valid ad ID.
 * @author roscoe
 *
 */
public class MediaCacheServlet implements IServlet {
	// Let's not cache EVERYTHING any rando throws at us.
	private List<String> acceptExtensions = Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".svg", ".mp4", ".mpeg4", ".m4v", ".webm");
	
	// Don't cache more than 10mb? TODO Think about this for video purposes...
	private long maxSize = 10000000L;
	
	private File webRoot = new File("web/mediacache");
	{
		if (!webRoot.exists()) {
			boolean ok = webRoot.mkdirs();
			if (!ok) throw new FailureException("Could not create directory " + webRoot);
		}
	}
	
	private Base64.Decoder decoder = Base64.getUrlDecoder();
	
	@Override
	public void process(WebRequest state) throws IOException {
		URL reqUrl = new URL(state.getRequestUrl());
		// Only the adunit (or someone who has taken a few minutes to figure out the cache system) is allowed to cache new files
		if (!"good-loop-ad-unit".equals(state.get("from"))) {
			WebUtils2.sendError(403, "You're not allowed to use this service", state.getResponse());
			return;
		}
		
		// Strip off leading path components to get filename (we'll be storing to this)
		String filename = reqUrl.getPath().replaceAll("^.*\\/", "");
		
		// Is someone trying to cache something they shouldn't?
		String extension = filename.substring(filename.lastIndexOf('.'));
		if (!acceptExtensions.contains(extension)) {
			WebUtils2.sendError(403, "We don't cache this file type: " + extension, state.getResponse());
			return;
		}
		
		// Strip off trailing extension to get the encoded URL and decode it
		String origUrlEncoded = filename.replaceAll("\\..*$", "");
		String origUrl = new String(decoder.decode(origUrlEncoded));
		
		URL fart = new URL(origUrl);
		HttpURLConnection conn = (HttpURLConnection) fart.openConnection();
		long size = Integer.parseInt(conn.getHeaderField("Content-Length"));
		if (size > maxSize) {
			WebUtils2.sendError(403, "File too big to cache: " + (size / 1024) + "KB", state.getResponse());
		}
		conn.disconnect();
				
		// Fetch the file. Could reuse the connection we used to check size but this skips a LOT of boilerplate
		FakeBrowser fb = new FakeBrowser();
		File tmpFile = fb.getFile(origUrl);
		
		// Move the file to the location it was originally requested from, so future calls will be a file hit
		File destFile = FileUtils.getNewFile(new File(webRoot.getAbsolutePath(), filename));
		FileUtils.move(tmpFile, destFile);
		
		// Redirect the caller to the same place they originally tried - which will now be a file hit
		// Remove the query string so it's not a circular redirect
		URL redirectUrl = new URL(reqUrl.getProtocol(), reqUrl.getHost(), reqUrl.getPort(), reqUrl.getPath());
		state.setRedirect(redirectUrl.toString());
		state.sendRedirect();
	}
}