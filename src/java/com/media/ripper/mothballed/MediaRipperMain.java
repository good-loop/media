package com.media.ripper.mothballed;

import com.winterwell.utils.Dep;
import com.winterwell.utils.threads.ATask;
import com.winterwell.utils.threads.TaskRunner;
import com.winterwell.web.app.AMain;
import com.winterwell.web.app.JettyLauncher;
import com.winterwell.web.app.MasterServlet;

public class MediaRipperMain extends AMain<MediaRipperConfig> {

	public static void main(String[] args) {
		MediaRipperMain mymain = new MediaRipperMain();
		mymain.doMain(args);
	}
	
	public MediaRipperMain() {
		super("mediaripper", MediaRipperConfig.class);
	}
	
	@Override
	protected void init2(MediaRipperConfig config) {
		super.init2(config);
	}
	
	@Override
	protected void addJettyServlets(JettyLauncher jl) {
		super.addJettyServlets(jl);
		MasterServlet ms = jl.addMasterServlet();	
		ms.addServlet("/rip", RipperServlet.class);
	}
	
}

