package org.frontcache.tags;

@SuppressWarnings("serial")
public class IncludeTag extends IncludeSupport {

   public void setUrl(Object url) {
      super.ulr = url;
   }

   public void setCall(Object callType) {
	   super.includeCallType = callType;
   }

   public void setClient(Object clientType) {
	   super.includeClientType = clientType;
   }
   
}