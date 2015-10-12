package org.frontcache.include.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Properties;
import java.util.logging.Logger;

import org.frontcache.include.IncludeProcessor;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public class BasicIncludeProcessor implements IncludeProcessor {

	
	private Logger logger = Logger.getLogger(getClass().getName());

	
	public BasicIncludeProcessor() {
	}

	
	
	/**
	 * 
	 * @param content
	 * @param appOriginBaseURL
	 * @return
	 */
	public String processIncludes(String content, String appOriginBaseURL)
	{
		StringBuffer outSb = new StringBuffer();
		final String START_MARKER = "<fc:include";
		final String END_MARKER = "/>";
		
		int scanIdx = 0;
		while(scanIdx < content.length())
		{
			int startIdx = content.indexOf(START_MARKER, scanIdx);
			if (-1 < startIdx)
			{
				int endIdx = content.indexOf(END_MARKER, startIdx);
				if (-1 < endIdx)
				{
					String includeTagStr = content.substring(startIdx, endIdx + END_MARKER.length());
					String includeURL = getIncludeURL(includeTagStr);
					
					outSb.append(content.substring(scanIdx, startIdx));
					
					String includeContent = call(appOriginBaseURL + includeURL);
					
					outSb.append(includeContent);
					
					scanIdx = endIdx + END_MARKER.length();
				} else {
					// can't find closing 
					outSb.append(content.substring(scanIdx, content.length()));
					scanIdx = content.length(); // scan complete
				}
				
				
			} else {
				outSb.append(content.substring(scanIdx, content.length()));
				scanIdx = content.length(); // scan complete
			}
		}

		return outSb.toString(); 
	}	


	/**
	 * 
	 * @param content
	 * @return
	 */
	private String getIncludeURL(String content)
	{
		logger.fine("include tag - " + content);
		final String START_MARKER = "url=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String urlValue = content.substring(startIdx + START_MARKER.length(), endIdx);
				logger.fine("include URL - " + urlValue);
				return urlValue;
			} else {
				// can't find closing 
				return null;
			}
			
			
		} else {
			// no url attribute
			return null;
		}

	}
	
	
	private String call(String url)
	{
		String data = new String(callBin(url));
		logger.info("include call - " + url);
		return data;
	}

    private byte[] callBin(String url)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        InputStream is = null;
        try {
            URL u = new URL(url);
            URLConnection uc = u.openConnection();
            is = uc.getInputStream();
            int bytesRead = 0;
            int bufferSize = 4000;
             byte[] byteBuffer = new byte[bufferSize];              
             while ((bytesRead = is.read(byteBuffer)) != -1) {
                 baos.write(byteBuffer, 0, bytesRead);
             }
        } catch (Exception e) {
        	e.printStackTrace();
            // TODO Auto-generated catch block
//            Logger.error(ImageUtils.class, e);
        } finally {
            if (null != is)
            {
                try {
                    is.close();
                } catch (IOException e) {
                	e.printStackTrace();
                    // TODO Auto-generated catch block
//                    Logger.error(ImageUtils.class, e);
                }
            }
        }
        return baos.toByteArray();
    }



	@Override
	public void init(Properties properties) {
		// TODO Auto-generated method stub
		
	}



	@Override
	public void destroy() {
		// TODO Auto-generated method stub
		
	}	
	
	
}
