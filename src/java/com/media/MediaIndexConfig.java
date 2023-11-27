package com.media;

import com.winterwell.es.ESPath;
import com.winterwell.es.IESRouter;
import com.winterwell.es.StdESRouter;
import com.winterwell.utils.io.Option;
import com.winterwell.web.app.ISiteConfig;
import com.winterwell.web.app.AppUtils;

public class MediaIndexConfig implements IESRouter, ISiteConfig {

	@Option
	public int port = 8745;

	@Override
	public int getPort() {
		return port;
	}

	/** Note that this is taken from AppUtils.Java, not a config file directly as in GLBaseConfig ( 
	 * 
	 */
	@Override
	public <T> ESPath<T> getPath(CharSequence dataspace, Class<T> type, CharSequence id, Object status) {
		return new StdESRouter().getPath(dataspace, type, id, status);
	}

}
