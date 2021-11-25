package com.media;

import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import com.winterwell.utils.FailureException;
import com.winterwell.utils.Proc;
import com.winterwell.utils.Utils;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.time.Time;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.FakeBrowser;
import com.winterwell.web.WebEx;
import com.winterwell.web.WebInputException;
import com.winterwell.web.ajax.JSend;
import com.winterwell.web.ajax.JsonResponse;
import com.winterwell.web.ajax.KAjaxStatus;
import com.winterwell.web.app.AppUtils;
import com.winterwell.web.app.FileServlet;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.KServerType;
import com.winterwell.web.app.WebRequest;
import com.winterwell.web.fields.StringField;

/**
 * GET /fonts/_list to get a list of uploaded raw fonts & their locations.
 * GET /fonts/uploaded_font_filename.ttf/_list to get a list of available subsets.
 * TODO POST /fonts/uploaded_font_filename.ttf/_refresh to reprocess the font for subsets.
 * Where do fonts go?
 * 
 * 
 * @author roscoe
 *
 */
public class FontServlet implements IServlet {
	private File webRoot = new File("web/");
	
	private File fontsRoot = new File(webRoot, "uploads/fonts/");
	{
		if (!fontsRoot.exists()) {
			boolean ok = fontsRoot.mkdirs();
			if (!ok) throw new FailureException("Could not create directory " + fontsRoot);
		}
	}
	
	@Override
	public void process(WebRequest state) throws IOException {
		WebUtils2.CORS(state, false);
		
		if (!state.isGET()) {
			throw new WebEx.E40X(405, "This endpoint only supports GET requests");
		}
		String[] slugBits = state.getSlugBits();
		
		if (slugBits.length > 1) {
			throw new WebEx.E400("GET /fonts to see all uploaded fonts, or GET /fonts/[font_name] to see available subsets of a font");
		}
		
		// GET media.good-loop.com/fonts = list base font names
		if (slugBits.length == 0) {
			File[] list = fontsRoot.listFiles();
			List<String> fontNames = new ArrayList<String>();
			for (File subdir : list) {
				fontNames.add(subdir.getName());
			}
			java.util.Collections.sort(fontNames);
			JSend send = new JSend();
			send.setStatus(KAjaxStatus.success);
			send.setData(new ArrayMap("fonts", fontNames));
			send.send(state);
			return;
		} else {
			// GET media.good-loop.com/fonts/$fontname = list + give URLs for subsets available for $fontname
			// Get base server URL to construct subset URLs
			KServerType serverType = AppUtils.getServerType(state);
			String server = AppUtils.getServerUrl(serverType, "media.good-loop.com").toString();
			String fontName = slugBits[0];
			
			File fontDir = new File(fontsRoot, fontName);
			if (!fontDir.exists()) {
				throw new WebEx.E400("The requested font \"" + slugBits[0] + "\" does not exist on this server.");
			}
			File[] list = fontDir.listFiles(); // [EN.woff, EN.woff2, DE.woff, DE.woff2, FR.woff, FR.woff2...]
			
			
			// Construct map { "EN": { "woff": "https://url-to-woff", "woff2": "https://url-to-woff2" }, "DE": { "woff": ...etc   
			Map<String, Object> subsets = new HashMap<String, Object>();
			for (File subsetFile : list) {
				String subset = FileUtils.getBasename(subsetFile); // EN, DE, FR...
				String type = FileUtils.getType(subsetFile); // woff, woff2
				// https://servername/uploads/fonts/$fontname/$subset.$type
				String subsetUrl = server + "/uploads/fonts/" + fontName + "/" + subsetFile.getName();
				
				// create or get + insert into sub-map
				Map<String, String> files = (Map<String, String>)subsets.get(subset);
				if (files == null) {
					files = new ArrayMap<String, String>(type, subsetUrl);
					subsets.put(subset, files);
				} else {
					files.put(type, subsetUrl);
				}
			}
			JSend send = new JSend();
			send.setStatus(KAjaxStatus.success);
			send.setData(new ArrayMap("subsets", subsets));
			send.send(state);
			return;
		}
		
		
	}
}