package com.media;

import java.io.File;

import com.winterwell.es.ESPath;
import com.winterwell.es.IESRouter;
import com.winterwell.utils.io.Option;
import com.winterwell.web.app.ISiteConfig;

public class MediaConfig implements IESRouter, ISiteConfig {

	@Option
	public int port = 8888;

	@Override
	public int getPort() {
		return port;
	}

	@Option(description="How big can an individual video file be? e.g. 10mb or 1gb")
	public String maxVideoUpload = "50mb";
	
	@Option
	public File uploadDir;
	
	@Override
	public <T> ESPath<T> getPath(CharSequence dataspace, Class<T> type, CharSequence id, Object status) {
		// TODO Auto-generated method stub
		return null;
	}
}
