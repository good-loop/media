package com.media;

import java.io.File;

import com.winterwell.utils.Dep;
import com.winterwell.utils.io.ConfigBuilder;
import com.winterwell.utils.log.Log;
import com.winterwell.web.app.AppUtils;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.KServerType;
import com.winterwell.web.app.UploadServlet;
import com.winterwell.web.app.WebRequest;

/** Wraps generic UploadServlet with media.good-loop specific configs **/ 
public class MediaUploadServlet implements IServlet {
	@Override
	public void process(WebRequest state) throws Exception {
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
		
		// Pass to UploadServlet
		servlet.process(state);
	}
}