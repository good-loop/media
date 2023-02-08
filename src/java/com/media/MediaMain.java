package com.media;

import java.io.File;

import com.winterwell.utils.Dep;
import com.winterwell.web.app.AMain;
import com.winterwell.web.app.HttpServletWrapper;
import com.winterwell.web.app.JettyLauncher;
import com.winterwell.youagain.client.YouAgainClient;

public class MediaMain extends AMain<MediaConfig> {
	
	public static void main(String[] args) {
		main = new MediaMain();
		main.doMain(args);	
	}
	
	
	public MediaMain() {
		super("media", MediaConfig.class);
	}
	
	@Override
	protected void init2(MediaConfig config) {
		// Set upload directory 
		if (config.uploadDir==null) {
			config.uploadDir = new File("web/uploads");
		}
		// YA
		YouAgainClient yac = new YouAgainClient("good-loop",getAppNameFull());
		//		DB.init(config); already done
		Dep.set(YouAgainClient.class, yac);		
		// Set-up youagain service
		super.init2(config);
	}
	
	@Override
	protected File getWebRootDir() {
		return new File("web");
	}
	
	@Override
	protected void addJettyServlets(JettyLauncher jl) {
		jl.addServlet("/uploads/mediacache/*", new HttpServletWrapper(MediaCacheServlet::new));
		jl.addServlet("/fonts/*", new HttpServletWrapper(FontServlet::new));
		jl.addServlet("/*", new HttpServletWrapper(MediaUploadServlet::new));
		super.addJettyServlets(jl);
	}
}
