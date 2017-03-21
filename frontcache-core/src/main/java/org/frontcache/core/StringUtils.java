/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
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

	public static boolean isEmpty(Object str) {
		return (str == null || "".equals(str));
	}
  
}