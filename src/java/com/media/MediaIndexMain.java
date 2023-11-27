package com.media;

import java.io.File;

import com.media.data.UploadedThing;
import com.winterwell.data.KStatus;
import com.winterwell.es.ESPath;
import com.winterwell.es.IESRouter;
import com.winterwell.es.client.ESConfig;
import com.winterwell.es.client.ESHttpClient;
import com.winterwell.utils.Dep;
import com.winterwell.web.ajax.JThing;
import com.winterwell.web.app.AMain;
import com.winterwell.web.app.AppUtils;
import com.winterwell.web.app.JettyLauncher;
import com.winterwell.web.app.MasterServlet;
import com.winterwell.youagain.client.YouAgainClient;

public class MediaIndexMain extends AMain<MediaIndexConfig> {
	public static void main(String[] args) {
		new MediaIndexMain().doMain(args);
	}

	public MediaIndexMain() {
		super("mediaindex", MediaIndexConfig.class);
	}
	
	@Override
	protected void init2(MediaIndexConfig config) {
		// YA
		YouAgainClient yac = new YouAgainClient("good-loop", getAppNameFull());
		//		DB.init(config); already done
		Dep.set(YouAgainClient.class, yac);		
		// Set-up youagain service
		super.init2(config);
		// config
		ESConfig esc = AppUtils.getConfig(getAppNameLocal(), ESConfig.class, null);
		Dep.set(IESRouter.class, config);
		Dep.setSupplier(ESHttpClient.class, true, () -> new ESHttpClient(Dep.get(ESConfig.class)));
		// Create indices for UploadedThing
		AppUtils.initESIndices(KStatus.main(), new Class[] { UploadedThing.class });
		
		
		
		// Next: on startup, use File interface to get directory listing for raw uploads and create UploadedThings
		IESRouter esr = Dep.get(IESRouter.class);
		ESPath<UploadedThing> testObjPath = esr.getPath(UploadedThing.class, "abcde");
		Object existingThing = null;
		//existingThing = AppUtils.get(testObjPath, String.class);
		try {
			existingThing = AppUtils.get(testObjPath, String.class);
		} catch (Exception e) {}
		if (existingThing == null) {
			UploadedThing ut = new UploadedThing();
			ut.id = "abcde";
			ut.url = "https://uploadedthing.test";
			AppUtils.doSaveEdit(testObjPath, new JThing<UploadedThing>(ut), null, null);
		}
		 
	}
	
	@Override
	protected File getWebRootDir() {
		return new File("web");
	}
	
	@Override
	protected void addJettyServlets(JettyLauncher jl) {
		super.addJettyServlets(jl);
		MasterServlet ms = jl.addMasterServlet();
		// version 0 (alpha)
		ms.addServlet("/mediaindex", new MediaIndexServlet());
	}
}
