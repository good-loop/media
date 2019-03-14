package com.media;

import java.util.List;

import com.winterwell.bob.BuildTask;
import com.winterwell.bob.tasks.MavenDependencyTask;
import com.winterwell.bob.wwjobs.BuildWinterwellProject;

public class BuildMedia extends BuildWinterwellProject {

	public BuildMedia() {
		super("media");
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
		mdt.addDependency("org.im4java", "im4java", "1.4.0");
		deps.add(mdt);

		return deps;
	}
	
}
