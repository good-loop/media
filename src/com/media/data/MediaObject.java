package com.media.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.winterwell.utils.Proc;
import com.winterwell.utils.StrUtils;
import com.winterwell.utils.Utils;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.time.Dt;
import com.winterwell.utils.time.TUnit;

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
			Proc proc = new Proc("mediainfo "+videoFile.getAbsolutePath());
			proc.start();
			int ok = proc.waitFor(2000);
			String output = proc.getOutput();
			
			for(String line : StrUtils.splitLines(output)) {
				int i = line.indexOf(":");
				if (i==-1) continue;
				String k = line.substring(0, i).trim();
				String v = line.substring(i+1).trim();
				if (v.isEmpty() || !k.equals("Duration")) continue;
				
				Pattern pnu = Pattern.compile("(\\d+)([a-z]+)");
				Matcher nu = pnu.matcher(v);
				Dt d = new Dt(0, TUnit.SECOND);
				while(nu.find()) {
					TUnit u = com.winterwell.utils.time.TimeUtils.getTUnit(nu.group(2));
					Dt b = new Dt(Double.parseDouble(nu.group(1)), u);
					d = d.plus(b);
				}
				return d;
			}
		}

		return null;
	}
}