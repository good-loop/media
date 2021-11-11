package com.media.ripper.mothballed;

import com.winterwell.web.app.ISiteConfig;

public class MediaRipperConfig implements ISiteConfig {

	@Override
	public int getPort() {
		return 8488;
	}

}
