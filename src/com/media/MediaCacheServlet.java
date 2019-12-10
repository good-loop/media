package com.media;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Base64;

import com.winterwell.utils.FailureException;
import com.winterwell.utils.StrUtils;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.FakeBrowser;
import com.winterwell.web.app.AppUtils;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.KServerType;
import com.winterwell.web.app.WebRequest;


public class MediaCacheServlet implements IServlet {
	private File webRoot = new File("web/mediacache");
	{
		if (!webRoot.exists()) {
			boolean ok = webRoot.mkdirs();
			if (!ok) throw new FailureException("Could not create directory " + webRoot);
		}
	}
	
	private Base64.Encoder encoder = Base64.getEncoder();
	
	@Override
	public void process(WebRequest state) throws IOException {
		
		URL origUrl = new URL(state.get("orig"));
		String origPath = origUrl.getPath();
		// Preserve .png etc - MIME type of served file appears to depend on this?
		String extension = origPath.substring(origPath.lastIndexOf('.'));
		
		FakeBrowser fb = new FakeBrowser();
		File targetFile = fb.getFile(origUrl.toString());
				
		// File name will be base64-encoded original URL (for filesystem safety) + original extension
		String encName = new String(encoder.encode(origUrl.toString().getBytes())) + "." + extension;
		
		File destFile = FileUtils.getNewFile(new File(webRoot.getAbsolutePath(), encName));
		FileUtils.move(targetFile, destFile);
		
		KServerType serverType = AppUtils.getServerType(state);
		String baseUrl = AppUtils.getServerUrl(serverType, "media.good-loop.com").toString();
		
		String destUrl = baseUrl + "/mediacache/" + destFile.getName();
				
		state.setRedirect(destUrl);
		state.sendRedirect();
	}
}