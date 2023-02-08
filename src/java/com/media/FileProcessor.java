package com.media;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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
 * Font processing requires Python fontTools and FontForge
 * (sudo pip3 install fonttools, sudo apt install fontforge)
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
	public static final List<String> PROCESSABLE_IMAGE_TYPES = Arrays.asList("jpg", "jpeg", "png");
	private static final String LOGTAG = null;
	
	public File rawDest;
	public File standardDest;
	public File mobileDest;
	// Custom destinations to allow overriding default raw/standard/mobile
	public Map<String, File> dests;

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
	public static FileProcessor imageProcessor(File rawDest, File standardDest, File mobileDest) {
		String inputImagePath = rawDest.getAbsolutePath();
		String standardImagePath = standardDest.getAbsolutePath();
		String lowResImagePath = mobileDest.getAbsolutePath();
		
		String fileType = FileUtils.getType(rawDest.getName());
		
		String command = "";
		
		// Only process JPG, JPEG, PNG - other types just copy into /standard and /mobile		
		if(!PROCESSABLE_IMAGE_TYPES.contains(fileType) || rawDest.length() < MINIMUM_IMAGE_SIZE) {
			Log.d("Image file is below 50kB. No processing will be done, but the raw image will be copied to the standard, mobile and raw directories");
			// TODO Symlink instead of copying, like MediaCacheServlet
			command = "cp " + inputImagePath + " " + standardImagePath
					+ "; " + "cp " + inputImagePath + " " + lowResImagePath;
		} else {
			if (JPEGOPTIM_SUPPORTED_TYPES.contains(fileType)) {
				command = "/usr/bin/convert " + inputImagePath + " -quality " + STANDARD_RES_QUALITY + " " + standardImagePath
						+ (JPEGOPTIM_SUPPORTED_TYPES.contains(fileType) ? "&& /usr/bin/jpegoptim " + standardImagePath : "")
						+ "; " + "/usr/bin/convert " + inputImagePath + " -quality " + LOW_RES_QUALITY + " " + lowResImagePath
						+ (JPEGOPTIM_SUPPORTED_TYPES.contains(fileType) ? "&& /usr/bin/jpegoptim " + lowResImagePath : "");
			} else {
				// optipng will iterate through different png compression methods and keep the result with smallest file size
				command = "cp " + inputImagePath + " " + standardImagePath + "; "
						// Change image mode to indexed (lossy but preserves alpha)
						+ "/usr/local/bin/pngquant -s1 --strip --verbose --skip-if-larger --force --ext .png " + standardImagePath + "; "
						// Optimise compression as much as possible
						+ "/usr/bin/zopflipng -y -m" + standardImagePath + " " + standardImagePath + "; "
						// Copy result to mobile directory
						+ "cp " + standardImagePath + " " + lowResImagePath;
			}
		}
		
		List<String> commands = Arrays.asList("/bin/bash", "-c", command);
		
		return new FileProcessor(rawDest, standardDest, mobileDest, commands);
	}


	public static FileProcessor videoProcessor(File rawDest, File standardDest, File mobileDest, Map params) {
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
//							? " --crop " + cropFactors.stream().reduce( "", (out, s) -> out + (s != null ? (String) s : "0") + ":")
//							: "";
		// Cropping is now explicitly OFF. 2021-06-17 DA
		// Switching from HandBrake-CLI to ffmpeg -- HandBrake-CLI no longer is up-to-date on ubuntu 18.04
		// Changing scale from e.g. 1280:720 to 720:720:force_original_aspect_ratio=increase:force_divisible_by=2
		// Meaning - start by assuming we'll scale to a 720x720 box, but increase whichever dimension is necessary
		// to maintain original aspect ratio, and round resultant size to a multiple of 2 to satisfy the encoder.
		// This allows the same command to handle 4:3, 16:9, portrait, and various non-standard aspect ratios. --RM August 2021
		String command = "taskset -c 0,1,2,3 ffmpeg -y -i " + inputVideoPath + " -ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 500k -maxrate 500k -bufsize 1000k -pix_fmt yuv420p -vf colorspace=bt709:iall=bt601-6-625:fast=1,scale=480:480:force_original_aspect_ratio=increase:force_divisible_by=2,setsar=1:1,deblock=filter=strong:block=4 -level:v 3.1 " + lowResVideoPath
				+ "; taskset -c 0,1,2,3 ffmpeg -y -i " + inputVideoPath + " -ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 750k -maxrate 750k -bufsize 1500k -pix_fmt yuv420p -vf colorspace=bt709:iall=bt601-6-625:fast=1,scale=720:720:force_original_aspect_ratio=increase:force_divisible_by=2,setsar=1:1,deblock=filter=strong:block=4 -level:v 3.1 " + highResVideoPath;
		List<String> commands = Arrays.asList("/bin/bash", "-c", command);
		
		return new FileProcessor(rawDest, standardDest, mobileDest, commands);
	}
	
	/** List of files containing character subsets for languages / language groups in /media/src/resources/orthographies/ */
	static List<String> orthographies = Arrays.asList("DE", "EN", "ES", "FR", "IT", "PT", "ARABIC", "CYRILLIC", "EASTERN_EUROPE", "NORDIC", "numerals");

	static String subsetCmdBase = "pyftsubset $INPUT --unicodes-file=/home/winterwell/media/src/resources/orthographies/$SUBSET.txt --output-file=$OUTPUT;";
	static String convertCmdBase = "fontforge -lang=ff -c 'Open(\"$INPUT\"); Generate(\"$OUTPUT\")';";


	/**
	 * Run an uploaded font through Python fontTools pyftsubset and FontForge conversion
	 * to generate a set of much smaller, language-specific, web-ready font files.
	 * @param rawFont Original font in TTF, OTF, WOFF, WOFF2
	 * @param fontDir Directory to output all generated fonts
	 * @return
	 */
	public static FileProcessor fontProcessor(File rawFont, File fontDir) {
		String inputFontPath = rawFont.getAbsolutePath();

		Map<String,File> dests = new HashMap<String, File>();

		List<String> commands = new ArrayList();
		commands.add("/bin/bash");
		commands.add("-c");
		String processSubsetsCommand = "";

		// Generate a non-subsetted, but converted, version of the font
		File completeWoffDest = new File(fontDir, "all.woff");
		File completeWoff2Dest = new File(fontDir, "all.woff2");
		String completeConvertCmd = convertCmdBase.replace("$INPUT", rawFont.getAbsolutePath());
		processSubsetsCommand += completeConvertCmd.replace("$OUTPUT", completeWoffDest.getAbsolutePath());
		processSubsetsCommand += completeConvertCmd.replace("$OUTPUT", completeWoff2Dest.getAbsolutePath());
		dests.put("all-woff", completeWoffDest);
		dests.put("all-woff2", completeWoff2Dest);

		// Generate all subsets right now in case we need them later
		for (String subset : orthographies) {
			// Subset raw.otf to eg EN.otf, then convert to EN.woff and EN.woff2
			File subsetDest = new File(fontDir, subset + "-base-subset." + FileUtils.getType(rawFont));
			File woffDest = new File(fontDir, subset + ".woff");
			File woff2Dest = new File(fontDir, subset + ".woff2");

			// Construct the pyftsubset command with input, output and subset
			String subsetCmd = subsetCmdBase.replace("$INPUT", inputFontPath);
			subsetCmd = subsetCmd.replace("$SUBSET", subset);
			subsetCmd = subsetCmd.replace("$OUTPUT", subsetDest.getAbsolutePath());

			// Construct the fontforge command with input = output of subset command
			String convertCmd = convertCmdBase.replace("$INPUT", subsetDest.getAbsolutePath());

			// Subset, convert to WOFF, convert to WOFF2, delete the unconverted subset.
			processSubsetsCommand += (
					subsetCmd
					+ convertCmd.replace("$OUTPUT", woffDest.getAbsolutePath())
					+ convertCmd.replace("$OUTPUT", woff2Dest.getAbsolutePath())
					+ "rm " + subsetDest.getAbsolutePath() + ";"
			);
			// Add destination files to output set
			dests.put(subset + "-woff", woffDest);
			dests.put(subset + "-woff2", woff2Dest);
		}
		// Convert uploaded font to WOFF and WOFF2
		commands.add(processSubsetsCommand);

		FileProcessor fp = new FileProcessor(rawFont, fontDir, null, commands);
		fp.dests = dests; // Set custom destination list
		return fp;
	}


	/** Perform the given conversion operation 
	 *  Need to provide pool of threads to run from **/
	public Map<String, MediaObject> run(ExecutorService pool) {
		pool.submit(() -> {
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
			} finally {
				FileUtils.close(process);
			}
		});
		
		Map out = new ArrayMap();
		
		out.put("raw", new MediaObject(this.rawDest));
		// Default or custom output files?
		if (this.dests == null) {
			out.put("standard", new MediaObject(this.standardDest));
			out.put("mobile", new MediaObject(this.mobileDest));
		} else {
			for (Map.Entry<String, File> destEntry : dests.entrySet()) {
				out.put(destEntry.getKey(), new MediaObject(destEntry.getValue()));
			}
		}

		return out;
	}
}