package org.frontcache.include;

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
public abstract class IncludeProcessorBase implements IncludeProcessor {

	
	private Logger logger = Logger.getLogger(getClass().getName());

	protected static final String START_MARKER = "<fc:include";
	protected static final String END_MARKER = "/>";
	
	public IncludeProcessorBase() {
	}

	
	

	/**
	 * 
	 * @param content
	 * @return
	 */
	protected String getIncludeURL(String content)
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
	
	
	protected String callInclude(String url)
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
