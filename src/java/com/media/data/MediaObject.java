package com.media.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.winterwell.utils.Proc;
import com.winterwell.utils.StrUtils;
import com.winterwell.utils.Utils;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.time.Dt;
import com.winterwell.utils.time.TUnit;

/**
 * @deprecated (minor) Refactor to have MediaObject with ImageObject and VideoObject
 * 
 * @author Roscoe
 *
 */
public class MediaObject {
	public File file;
	// Will need to be set externally
	public Dt duration;
	
	
	public MediaObject(File file) {
		this.file = file;
	}
	
	public Dt calculateDuration() {
		return calculateDuration(this.file);
	}
	
	/** Very similar to calculation in {@link GLUtils}
	 * Wanted to just return Dt rather than VideoObject as, for the moment, data is returned before video processing is complete
	 * Meant that VideoObject could not be populated correctly
	 * TODO: come back and reasses that assumption
	 *  **/
	public static Dt calculateDuration(File videoFile) {
		if( videoFile.isFile() && FileUtils.isVideo(videoFile) ) {
			Proc proc = new Proc("mediainfo -f "+videoFile.getAbsolutePath());
			proc.start();
			int ok = proc.waitFor(2000);
			String output = proc.getOutput();
			
			for(String line : StrUtils.splitLines(output)) {
				int i = line.indexOf(":");
				if (i==-1) continue;
				String k = line.substring(0, i).trim();
				String v = line.substring(i+1).trim();
				if (v.isEmpty() || !k.equals("Duration")) continue;
				// Will return a list of "Durations" in different formats
				// Only care about the version in Milliseconds.
				// Expect this version to appear first
				try {
					return new Dt(TimeUnit.MILLISECONDS.toSeconds(Long.parseLong(v)), TUnit.SECOND);
				} catch(Exception e){
					continue;
				}
			}
		}

		return null;
	}
}