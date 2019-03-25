package com.media;


import java.util.List;

import com.winterwell.bob.BuildTask;
import com.winterwell.datalog.PublishDataServer;
import com.winterwell.web.app.build.KPubType;
import com.winterwell.web.app.build.PublishProjectTask;

/**
 * copy pasta of {@link PublishDataServer}
 * FIXME rsync is making sub-dirs :(
 */
public class PublishMedia extends PublishProjectTask {
			
	public PublishMedia() {
		super("media", "/home/winterwell/media.good-loop.com");
		typeOfPublish = KPubType.test;
	}

	@Override
	public List<BuildTask> getDependencies() {
		List<BuildTask> deps = super.getDependencies();
//		deps.add(new BuildYouAgainServer().setCompile(false));
		return deps;
	}

	@Override
	protected void doTask() throws Exception {
		super.doTask();		
	}
}
