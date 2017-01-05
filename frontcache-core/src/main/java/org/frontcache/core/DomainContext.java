package org.frontcache.core;

public class DomainContext {

	private String domain;
	private String siteKey;
	private String host;
	private String httpPort;
	private String httpsPort;
	
		
	public DomainContext(String domain, String siteKey, String host, String httpPort, String httpsPort) {
		super();
		this.domain = domain;
		this.siteKey = siteKey;
		this.host = host;
		this.httpPort = httpPort;
		this.httpsPort = httpsPort;
	}
	
	public String getDomain() {
		return domain;
	}


	public String getHost() {
		return host;
	}

	public String getHttpPort() {
		return httpPort;
	}


	public String getHttpsPort() {
		return httpsPort;
	}

	public String getSiteKey() {
		return siteKey;
	}

	public void setSiteKey(String siteKey) {
		this.siteKey = siteKey;
	}

	@Override
	public String toString() {
		return "DomainContext [domain=" + domain + ", siteKey=" + siteKey + ", host=" + host + ", httpPort=" + httpPort
				+ ", httpsPort=" + httpsPort + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((domain == null) ? 0 : domain.hashCode());
		result = prime * result + ((host == null) ? 0 : host.hashCode());
		result = prime * result + ((httpPort == null) ? 0 : httpPort.hashCode());
		result = prime * result + ((httpsPort == null) ? 0 : httpsPort.hashCode());
		result = prime * result + ((siteKey == null) ? 0 : siteKey.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DomainContext other = (DomainContext) obj;
		if (domain == null) {
			if (other.domain != null)
				return false;
		} else if (!domain.equals(other.domain))
			return false;
		if (host == null) {
			if (other.host != null)
				return false;
		} else if (!host.equals(other.host))
			return false;
		if (httpPort == null) {
			if (other.httpPort != null)
				return false;
		} else if (!httpPort.equals(other.httpPort))
			return false;
		if (httpsPort == null) {
			if (other.httpsPort != null)
				return false;
		} else if (!httpsPort.equals(other.httpsPort))
			return false;
		if (siteKey == null) {
			if (other.siteKey != null)
				return false;
		} else if (!siteKey.equals(other.siteKey))
			return false;
		return true;
	}
	
}
