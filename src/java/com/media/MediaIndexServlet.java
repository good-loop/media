package com.media;

import com.media.data.UploadedThing;
import com.winterwell.es.ESPath;
import com.winterwell.es.IESRouter;
import com.winterwell.utils.web.WebUtils2;
import com.winterwell.web.app.CrudServlet;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.WebRequest;

public class MediaIndexServlet extends CrudServlet<UploadedThing>{
	
	public MediaIndexServlet() {
		super(UploadedThing.class);
		System.out.println("constructing...");
		// TODO Auto-generated constructor stub
	}
}
