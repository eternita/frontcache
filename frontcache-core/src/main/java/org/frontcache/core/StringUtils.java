package org.frontcache.core;

import java.util.List;

public class StringUtils {
	
  /**
   * return in inputStr substring between first and second
   * 
   * @param inputStr
   * @param first
   * @param second
   * @return
   */
  public static String getStringBetween(String inputStr, String first, String second)
  {
		String out = null;
		int idx = inputStr.indexOf(first);
		if (-1 < idx) 
		{
			String s = inputStr.substring(idx + first.length());
			int idx2 = s.indexOf(second);
			if (-1 < idx2)
				out = s.substring(0, idx2);
		}

		return out;
  }
  
  public static List<String> getListBetween(String inputStr, String first, String second, List<String> list)
  {
		String str = inputStr;
	  	int idx = str.indexOf(first);
	  	while (idx > 0)
	  	{
	  		list.add(getStringBetween(str, first, second));
	  		str = str.substring(idx + first.length());
	  		idx = str.indexOf(first);
	  	}
		return list;
  }
  
}