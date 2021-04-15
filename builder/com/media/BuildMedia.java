package com.media;

import java.util.List;

import com.winterwell.bob.BuildTask;
import com.winterwell.bob.tasks.MavenDependencyTask;
import com.winterwell.bob.wwjobs.BuildWinterwellProject;

public class BuildMedia extends BuildWinterwellProject {

	public BuildMedia() {
		super("media");
		setVersion("1.0.1"); // 15 Apr 2021
	}

	@Override
	public List<BuildTask> getDependencies() {
		List<BuildTask> deps = super.getDependencies();

		// add extra Maven deps??
		MavenDependencyTask mdt = new MavenDependencyTask();
		mdt.setProjectDir(projectDir);
		if (outDir!=null) {
			mdt.setOutputDirectory(outDir);
		}
		mdt.addDependency("commons-fileupload","commons-fileupload","1.4");
		deps.add(mdt);

		return deps;
	}
	
}
