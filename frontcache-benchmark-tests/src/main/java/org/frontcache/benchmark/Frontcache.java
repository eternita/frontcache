package org.frontcache.benchmark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Properties;

import org.frontcache.cache.impl.L1L2CacheProcessor;
import org.frontcache.core.WebResponse;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

public class Frontcache extends BaseBenchmark {

	private L1L2CacheProcessor indexManager;

	private static final String DOMAIN = "testdomain.com";

	@Setup
	public void setup() throws IOException {

		indexManager = new L1L2CacheProcessor();
		Properties prop = new Properties();
		prop.put("front-cache.cache-processor.impl.cache-dir", "/tmp/cache/");

		System.setProperty("frontcache.home", "/opt/frontcache");
		indexManager.init(prop);
		indexManager.removeFromCacheAll(DOMAIN);
		seedIndex();
	}

	@Benchmark
	public String benchmark() throws IOException {
		WebResponse fromFile = indexManager.getFromCacheImpl(getRandomUrl());
	
		return new String(fromFile.getContent());
	}

	private void seedIndex() throws IOException {

		Map<String, String> map = getfileMapping();
		for (String url : map.keySet()) {
			String file = map.get(url);
			String content = readResource(file);
			WebResponse res = new WebResponse(url);
			res.setContent(content.getBytes());
			res.setDomain(DOMAIN);
			indexManager.putToCache(DOMAIN, url, res);

		}

	}

	private String readResource(String name) throws IOException {
		StringBuilder builder = new StringBuilder();
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(Frontcache.class.getResourceAsStream("/templates/" + name)))) {
			for (;;) {
				String line = in.readLine();
				if (line == null) {
					break;
				}
				builder.append(line);
			}
		}
		return builder.toString();
	}

	public static void main(String[] args) throws IOException {
		Frontcache f = new Frontcache();
		f.setup();
	    f.benchmark();
		f.tearDown();
		
	}
	
	@TearDown
	public void tearDown() throws IOException {

		indexManager.removeFromCacheAll(DOMAIN);
		indexManager.destroy();
	}

}
