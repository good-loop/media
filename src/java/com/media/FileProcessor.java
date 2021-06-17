package com.media;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import com.media.data.MediaObject;
import com.media.data.MediaObject;
import com.winterwell.utils.Proc;
import com.winterwell.utils.Utils;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;

/**
 * See {@link MediaCacheServlet} for info on the directory structure this uses
 * and the non-Java dependencies e.g. imagemagick.
 * 
 * TODO @Roscoe - Could you add a unit test for this? Thanks.
 * 
 * @author Roscoe
 *
 */
public class FileProcessor {
	// TODO: Would it be worth selecting quality reduction based on some maximum size?
	// i.e always want mobile images below 300kB, so reduce image to get as close as possible without creating a grainy mess
	public static final String STANDARD_RES_QUALITY = "70";
	public static final String LOW_RES_QUALITY = "50";
	// Images already below 50kB do not require further processing
	public static final long MINIMUM_IMAGE_SIZE = 50000;
	// File types supported by jpegoptim
	public static final List<String> JPEGOPTIM_SUPPORTED_TYPES = Arrays.asList("jpg", "jpeg");
	
	public File rawDest;
	public File standardDest;
	public File mobileDest;
	public final List<String> commands;
	
	private FileProcessor(File rawDest, File standardDest, File mobileDest, List<String> commands) {
		this.rawDest = rawDest;
		// Moderately compressed version. Generated by either doProcessImage or doProcessVideo
		this.standardDest = standardDest;
		// Most compressed version. Generated by either doProcessImage or doProcessVideo
		this.mobileDest = mobileDest;
		
		this.commands = commands;
	}
	
	/** Assumes that there will already be an image in /uploads/raw for it to find **/
	public static FileProcessor ImageProcessor(File rawDest, File standardDest, File mobileDest) {
		String inputImagePath = rawDest.getAbsolutePath();
		String standardImagePath = standardDest.getAbsolutePath();
		String lowResImagePath = mobileDest.getAbsolutePath();
		
		String fileType = FileUtils.getType(rawDest.getName());
		
		String command = "";
		// Raw image will be placed in raw, standard and mobile
		if( rawDest.length() < MINIMUM_IMAGE_SIZE ) {
			Log.d("Image file is below 50kB. No processing will be done, but the raw image will be copied to the standard, mobile and raw directories");
			command = "cp " + inputImagePath + " " + standardImagePath
					+ "; " + "cp " + inputImagePath + " " + lowResImagePath;
		} else if(fileType.equals("png")) {
			// optipng will iterate through different png compression methods and keep the result with smallest file size
			command = "cp " + inputImagePath + " " + standardImagePath + "; "
					// Change image mode to indexed (lossy but preserves alpha)
					+ "/usr/local/bin/pngquant -s1 --strip --verbose --skip-if-larger --force  --ext .png " + standardImagePath + "; "
					// Optimise compression as much as possible
					+ "/usr/bin/optipng " + standardImagePath + "; "
					// Copy result to mobile directory
					+ "cp " +  standardImagePath + " " + lowResImagePath;
		} else {
			command = "/usr/bin/convert " + inputImagePath + " -quality " + STANDARD_RES_QUALITY + " " + standardImagePath
					+ (JPEGOPTIM_SUPPORTED_TYPES.contains(fileType) ? "&& /usr/bin/jpegoptim " + standardImagePath : "")
					+ "; " + "/usr/bin/convert " + inputImagePath + " -quality " + LOW_RES_QUALITY + " " + lowResImagePath
					+ (JPEGOPTIM_SUPPORTED_TYPES.contains(fileType) ? "&& /usr/bin/jpegoptim " + lowResImagePath : "");
		}
		List<String> commands = Arrays.asList("/bin/bash", "-c", command);
		
		return new FileProcessor(rawDest, standardDest, mobileDest, commands);
	}

	public static FileProcessor VideoProcessor(File rawDest, File standardDest, File mobileDest, Map params) {
		String inputVideoPath = rawDest.getAbsolutePath();
		String lowResVideoPath = mobileDest.getAbsolutePath();
		String highResVideoPath = standardDest.getAbsolutePath();
		
//		List<Object> cropFactors = Arrays.asList(
//				params.get("crop-top"),
//				params.get("crop-bottom"),
//				params.get("crop-left"),
//				params.get("crop-right")
//		);
//		
//		// Add cropping parameters if these have been specified
//		String cropCommand = ( cropFactors.stream().anyMatch(v -> v != null) ) 
//							? " --crop " + cropFactors.stream().reduce( "", (out, s) ->  out + (s != null ? (String) s : "0") + ":")
//							: "";
		// Cropping is now explicitly OFF.  2021-06-17 DA
		// Switching from HandBrake-CLI to ffmpeg -- HandBrake-CLI no longer is up-to-date on ubuntu 18.04
		String command = "taskset -c 0,1,2,3 ffmpeg " + "-i " + inputVideoPath + " -ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 500k -maxrate 500k -bufsize 1000k -vf scale=720:480,setsar=1:1,fps=24/1.001,deblock=filter=strong:block=4 -level:v 3.1 " + lowResVideoPath	
				+ "; " + "taskset -c 0,1,2,3 ffmpeg " + "-i " + inputVideoPath + " -ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 750k -maxrate 750k -bufsize 1500k -vf scale=1280:720,setsar=1:1,fps=24/1.001,deblock=filter=strong:block=4 -level:v 3.1 " + highResVideoPath;
		List<String> commands = Arrays.asList("/bin/bash", "-c", command);
		
		return new FileProcessor(rawDest, standardDest, mobileDest, commands);
	}
	
	/** Perform the given conversion operation 
	 *  Need to provide pool of threads to run from **/
	public Map<String, MediaObject> run(ExecutorService pool) {
		pool.submit(() -> {
				Proc process = new Proc(this.commands);
			try {
				process.start();
				process.waitFor();
				Log.d(process.getOutput());
				if ( ! Utils.isBlank(process.getError())) {
					Log.e(process.getError());
				}
			} catch (Throwable e) {
				Log.e(e);
			}
		});
		
		Map out = new ArrayMap();
		
		out.put("raw", new MediaObject(this.rawDest));		
		out.put("standard", new MediaObject(this.standardDest));
		out.put("mobile", new MediaObject(this.mobileDest));

		return out;
	}
}