package com.media;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;

import com.media.data.MediaObject;
import com.winterwell.utils.Proc;
import com.winterwell.utils.Utils;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;

/** 
 * For processes that result in a folder of multiple files which need their own processing (e.g. zips)
 */
class RecursiveFileProcessor extends FileProcessor {
	
	protected File standardDir;
	protected File mobileDir;
	protected File extractDir;
	
	protected RecursiveFileProcessor(File uploadsDir, File rawDest, File standardDir, File mobileDir, File extractDir, List<String> commands) {
		super(uploadsDir, rawDest, getDestFile(standardDir, rawDest), getDestFile(mobileDir, rawDest), commands);
		this.standardDir = standardDir;
		this.mobileDir = mobileDir;
		this.extractDir = extractDir;
	}
	
	/**
	 * Same function, but includes recursive logic for directories
	 * TODO this leaves empty dirs behind - but we can't predict they'll be empty beforehand, and the processing runs in async -
	 * the only code that knows when processing is done are the individual file processors, but they don't know when each other are done -
	 * so when do we check for empty dirs??
	 * @param pool
	 * @param uploadsDir
	 * @param rawDest
	 * @param standardDir
	 * @param mobileDir
	 * @param params
	 * @return
	 * @throws FileProcessException
	 */
	public static Map<String, MediaObject> process(ExecutorService pool, File uploadsDir, File rawDest, File standardDir, File mobileDir, Map params) throws FileProcessException {
		
		// Always make this check first
		if (Files.isSymbolicLink(rawDest.toPath())) throw new FileProcessException("No symbolic links allowed!");
		if (rawDest.isDirectory()) {
			// Directories cannot be uploaded - this is only called in recursive processes
			File[] subFiles = rawDest.listFiles();
			if (subFiles.length == 0) return new ArrayMap<>(); // no-op
			Map<String, MediaObject> output = new ArrayMap<>();
			File subStandardDir = FileUtils.getDirectory(standardDir, rawDest.getName());
			File subMobileDir = FileUtils.getDirectory(mobileDir, rawDest.getName());
			for (File subFile : subFiles) {
				Log.i("Recursively processing " + subFile.getName(), Level.FINE);
				Map<String, MediaObject> subOutput = RecursiveFileProcessor.process(pool, uploadsDir, subFile, subStandardDir, subMobileDir, params);
				for (Map.Entry<String, MediaObject> subPair : subOutput.entrySet()) {
					output.put(subFile.getName() + "/" + subPair.getKey(), subPair.getValue());
				}
			}
			return output;
		}
		
		return FileProcessor.process(pool, uploadsDir, rawDest, standardDir, mobileDir, params);
	}
	
	@Override
	public Map<String, MediaObject> run(ExecutorService pool, Map params) throws FileProcessException {
		
		// Run commands like normal
		Future<File> future = pool.submit(() -> {
			Proc process = new Proc(this.commands);
			try {
				Log.d(LOGTAG, "run "+process);
				process.start();
				process.waitFor();
				Log.d(process.getOutput());
				if ( ! Utils.isBlank(process.getError())) {
					if (process.getError().contains("SEAC-like endchar operator is deprecated")) {
						// this is an ignorable warning about a font accent character
						Log.d(LOGTAG, "(ignore warning) process: "+process+" >>> "+process.getError());
					}
					Log.e(LOGTAG, "process: "+process+" >>> "+process.getError());
				}
			} catch (Throwable e) {
				Log.e(LOGTAG, e);
				// remove any processed files
				FileUtils.delete(rawDest);
				for (Map.Entry<String, File> destEntry : dests.entrySet()) {
					FileUtils.delete(destEntry.getValue());
				}
			} finally {
				FileUtils.close(process);
			}
			return this.extractDir;
		});
		
		// Setup output map
		Map<String, MediaObject> out = new ArrayMap<>();
		
		// We know zip files use custom dests entry - no need to check
		for (Map.Entry<String, File> destEntry : dests.entrySet()) {
			out.put(destEntry.getKey(), new MediaObject(destEntry.getValue()));
		}
		
		// Recursively process extracted files
		try {
			File extractFolder = future.get();
			Map<String, MediaObject> subOut = RecursiveFileProcessor.process(pool, uploadsDir, extractFolder, standardDir, mobileDir, params);
			for (Map.Entry<String, MediaObject> pair : subOut.entrySet()) {
				out.put(pair.getKey() , pair.getValue());
			}
		} catch (Throwable e) {
			throw new FileProcessException("Interrupted while recursively processing files in " + this.rawDest.getName(), e);
		}
		
		return out;
		
	}
	
}