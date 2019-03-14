package com.media;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;

import org.im4java.process.ProcessStarter;

import com.winterwell.utils.Dep;
import com.winterwell.web.app.AMain;
import com.winterwell.web.app.HttpServletWrapper;
import com.winterwell.web.app.JettyLauncher;
import com.winterwell.youagain.client.YouAgainClient;

public class MediaMain extends AMain<MediaConfig> {
	
	/** Path to your imageMagick binary **/
	public static String imgMagickPath;
	
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
		initImageMagick();
		// YA
		YouAgainClient yac = new YouAgainClient("good-loop");
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
		jl.addServlet("/upload", new HttpServletWrapper(MediaUploadServlet::new));
		super.addJettyServlets(jl);
	}

	private void initImageMagick() {
		if( imgMagickPath != null ) return;
		
		// TODO: Have this find folder by dynamically searching for ImageMagick* directory. Worried that we'll get caught out by an update
		imgMagickPath = "/etc/apt/ImageMagick-7.0.8-33";
		ProcessStarter.setGlobalSearchPath(imgMagickPath);
	}
}
