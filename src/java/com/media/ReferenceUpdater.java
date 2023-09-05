package com.media;

import java.io.File;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;

/**
 * Replaces references as URLs in files
 * @author oem
 *
 */
public class ReferenceUpdater {
	
	public class RefUpdate {
		public String url;
		public String version;
		public RefUpdate (String url, String version) {
			this.url = url;
			this.version = version;
		}
		@Override
		public String toString () {
			return this.version + ", " + this.url;
		}
	}
	
	public Map<String, RefUpdate> updates = new ArrayMap<>();
	
	public void addReferenceUpdate (File oldRef, String newRef, String label, String preferVersion) {
		String version = getVersion(label);
		String filename = oldRef.getName();
		if (updates.containsKey(filename)) {
			// only replace the update if our version is the preferred version
			if (!updates.get(filename).version.equals(preferVersion) && version.equals(preferVersion)) {
				updates.put(filename, new RefUpdate(newRef, version));
			}
		} else {
			updates.put(filename, new RefUpdate(newRef, version));
		}
	}
	
	public void replaceReferences (File file) {
		
		// Can't read and replace non-text files - silently no-op
		if (!FileUtils.isWebDoc(file) && !FileUtils.isDocument(file)) return;
		
		String contents = FileUtils.read(file);
		
		for (Map.Entry<String, RefUpdate> update : updates.entrySet()) {
			String filename = update.getKey();
			// Any URL ending with this filename
			String regex = "((https?://)?/?[\\w-./]+/)?" + filename.replace(".", "\\.") + "/?";
			
			Pattern pattern = Pattern.compile(regex);
			Matcher m = pattern.matcher(contents);
			
			if (m.find()) {
				Log.d("!!!!FOUND URL in " + file.getName() + "::", m.group());
			}
			
			contents = m.replaceAll(update.getValue().url);
		}
		
		FileUtils.write(file, contents);
		
	}
	
	String getVersion (String assetLabel) {
		String[] split = assetLabel.split("/");
		String version = split[split.length - 1];
		return version;
	}
	
	@Override
	public String toString () {
		return updates.entrySet().toString();
	}
	
}
