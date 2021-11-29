package com.media;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.winterwell.utils.FailureException;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.WebEx;
import com.winterwell.web.ajax.JSend;
import com.winterwell.web.ajax.KAjaxStatus;
import com.winterwell.web.app.AppUtils;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.KServerType;
import com.winterwell.web.app.WebRequest;

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

			// Protect against /fonts/../../../../server-home-dir attacks
			if (!FileUtils.isSafe(fontName)) {
				throw new WebEx.E400("Given font name contains potentially dangerous components: " + fontName);
			}
			
			File fontDir = new File(fontsRoot, fontName);
			if (!fontDir.exists()) {
				throw new WebEx.E400("The requested font \"" + fontName + "\" does not exist on this server.");
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