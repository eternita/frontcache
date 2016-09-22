package org.frontcache.cache.impl.ehcache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.WebResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

public class FSEhcacheProcessor extends CacheProcessorBase implements CacheProcessor {

	private static final String DEFAULT_EHCACHE_CONFIG_FILE = "ehcache-config.xml";
	
	private static final String EHCACHE_CONFIG_FILE_KEY = "front-cache.cache-processor.impl.ehcache.config";
	
	private static final String FRONT_CACHE = "FRONT_CACHE"; // cache name inside config file (e.g. ehcache-config.xml)

	private static final String CACHE_RELATIVE_DIR = "cache/fs"; // sub-dir under FRONTCACHE_HOME
	
	private static String CACHE_BASE_DIR = "not initialized";
	
	private static final int MAX_FILE_NAME_LENGTH = 50;
	
	private static final String CACHE_FILE_EXTENSION = ".fc"; // need to avoid possible collision with last dir and file name (must contain '.')
	
	private CacheManager ehCacheMgr = null;
	
    private Cache cache = null;

	private  ObjectReader reader;
	
	private  ObjectWriter writer;
	
    long fileCounter = 0;
    
	@Override
	public void init(Properties properties) {
		
		ObjectMapper mapper = new ObjectMapper();
		reader = mapper.reader(WebResponse.class);
		writer = mapper.writerWithType(WebResponse.class);
		
		
		// 1. get input stream from system variable frontcache.home
		String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		File fsBaseDir = new File(new File(frontcacheHome), CACHE_RELATIVE_DIR);
		
		CACHE_BASE_DIR = fsBaseDir.getAbsolutePath();
		if (!CACHE_BASE_DIR.endsWith("/"))
			CACHE_BASE_DIR += "/";
		
		logger.info("Cache file storage: " + CACHE_BASE_DIR);
		 
		String ehCacheConfigFile = properties.getProperty(EHCACHE_CONFIG_FILE_KEY);
		if (null == ehCacheConfigFile)
		{
			logger.info(EHCACHE_CONFIG_FILE_KEY + " is required for " + getClass().getName() + " but not defined in config. Default is used: " + DEFAULT_EHCACHE_CONFIG_FILE);
			ehCacheConfigFile = DEFAULT_EHCACHE_CONFIG_FILE;
		}
		
		logger.info("Loading " + ehCacheConfigFile);
		InputStream is = FCConfig.getConfigInputStream(ehCacheConfigFile);
		ehCacheMgr = CacheManager.create(is);
		
		if (null != is)
		try {
			is.close();
		} catch (IOException e) {		}
		
        cache = ehCacheMgr.getCache(FRONT_CACHE);
        if (null == cache)
        {
        	ehCacheMgr.addCache(FRONT_CACHE);
            cache = ehCacheMgr.getCache(FRONT_CACHE);
        }
        
        
        // restore from files
        Runnable r = new Runnable () {

			@Override
			public void run() {
		        long start = System.currentTimeMillis();
		        
		        restoreFromFiles(new File(CACHE_BASE_DIR));
		        
		        logger.info("Cache restored from " + CACHE_BASE_DIR);
		        logger.info("Cache restore completed in " + (System.currentTimeMillis() - start)/1000L + " seconds " );
		        logger.info("Checked " + fileCounter + " files" );
			}
        	
        };
        
        Thread t = new Thread(r);
        t.start();
        
//        if(0 == cache.getKeys().size())
//        	restoreFromFiles(new File(CACHE_BASE_DIR));
        
        return;
	}

	@Override
	public void destroy() {
		
		try
		{
			if (null != cache)
				cache.dispose();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
		try
		{
			if (null != ehCacheMgr)
				ehCacheMgr.shutdown();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}	

	/**
	 * 
	 */
	@Override
	public void putToCache(String url, WebResponse component) {
		logger.debug(url);
		if (save2file(url, component))
			cache.put(new Element(url, "X"));
	}
	
	/**
	 * 
	 */
	@Override
	public WebResponse getFromCacheImpl(String url) {
		logger.debug(url);
		
		WebResponse comp = getFromFile(url);

		if (null != comp && comp.isExpired())
		{
			deleteFile(url);
			cache.remove(url);
			return null;
		}
		
		return comp;
	}

	@Override
	public void removeFromCache(String filter) {
		List<String> removeList = new ArrayList<String>();
		
		for(Object key : cache.getKeys())
		{
			String url = key.toString();
			if (-1 < url.indexOf(filter))
				removeList.add(url);	
		}
		
		for(String url : removeList)
		{
			deleteFile(url);
			cache.remove(url);
		}
	}

	@Override
	public void removeFromCacheAll() {
		deleteAllFiles();
		cache.removeAll();
	}
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = super.getCacheStatus();
		
		status.put("impl", this.getClass().getName());

		status.put("cached entiries", "" + cache.getKeys().size());
		
		return status;
	}
	
	@Override
	public List<String> getCachedKeys() {
		List<String> keys = new ArrayList<String>();
		for (Object key : cache.getKeys())
			keys.add(key.toString());

		return keys;
	}
	
	private void deleteAllFiles()
	{
		Path rootPath = Paths.get(CACHE_BASE_DIR);
		try {
			Files.walk(rootPath, FileVisitOption.FOLLOW_LINKS)
			    .sorted(Comparator.reverseOrder())
			    .map(Path::toFile)
//			    .peek(System.out::println)
			    .forEach(File::delete);
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("can't delete all files.", e);
		}		
		return;
	}
	
	private boolean save2file(String url, WebResponse component)
	{

		boolean success = true;
		String absoluteFilePath = url2filePath(url);

		int dirIdx = absoluteFilePath.lastIndexOf("/") + 1;
		String dirName = absoluteFilePath.substring(0, dirIdx);
		File dir = new File(dirName);
		if (!dir.exists())
			dir.mkdirs();
		
		File cacheFile = new File (absoluteFilePath);

		try {
			writer.writeValue(cacheFile, component);
		} catch (IOException e) {
			success = false;
			logger.error("can't save file for url " + url, e);
		}
		return success;		
	}
	
	private WebResponse getFromFile(String url)
	{
		String absoluteFilePath = url2filePath(url);
		File cacheFile = new File (absoluteFilePath);
		
		if (cacheFile.exists()) {
			try {
				WebResponse response = reader.readValue(cacheFile);
				return response;
			} catch (IOException e) {
				logger.error("can't read file for url " + url, e);
			}
		}
		return null;
	}

	private void deleteFile(String url)
	{
		String absoluteFilePath = url2filePath(url);
		File cacheFile = new File (absoluteFilePath);
		
		if (cacheFile.exists())
			cacheFile.delete();

		return;
	}
	
	private String url2filePath(String url) {
		
		char[] chars = url.toCharArray();
		StringBuffer hex = new StringBuffer(CACHE_BASE_DIR);
		for (int i = 0; i < chars.length; i++) {
			hex.append(Integer.toHexString((int) chars[i]));
			
			if (0 < i && 2*i % MAX_FILE_NAME_LENGTH == 0)
				hex.append("/");
		}
		hex.append(CACHE_FILE_EXTENSION);
		
		return hex.toString(); // absolute File Path
	}

	private String filepath2url(String absolutePath) {
		
		if (!absolutePath.endsWith(CACHE_FILE_EXTENSION))
			return null;
		
		String relativePath = absolutePath.substring(CACHE_BASE_DIR.length());
		relativePath = relativePath.replace("/", ""); // remove /
		relativePath = relativePath.replace(CACHE_FILE_EXTENSION, ""); // remove file extension
		
		String url = hex2string(relativePath); 
		return url;

	}
	
	/**
	 * recursively for all files
	 * get path, convert to URL, put to cache
	 * 
	 * @param f could be a file or a directory
	 */
	private void restoreFromFiles(File f) {
	      if(f.isFile()) {
	    	  
	    	  String url = filepath2url(f.getAbsolutePath());
	    	  if (null != url)
	    	  {
	    		  cache.put(new Element(url, "X"));
	    		  fileCounter++;
	    	  }
	    	  
	      } else {
		      // it's a directory
	    	  File[] files = f.listFiles();
	    	  if (null != files)
			      for(File entry : files) 
			    	  restoreFromFiles(entry);
		      
	      }
	      
	      return;
	  }

	private String hex2string(String hex){

		  StringBuilder sb = new StringBuilder();

		  //49204c6f7665204a617661 split into two characters 49, 20, 4c...
		  for( int i=0; i<hex.length()-1; i+=2 ){

		      //grab the hex in pairs
		      String output = hex.substring(i, (i + 2));
		      //convert hex to decimal
		      int decimal = Integer.parseInt(output, 16);
		      //convert the decimal to character
		      sb.append((char)decimal);
		  }

		  return sb.toString();
	  }	
	
}

