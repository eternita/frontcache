package org.frontcache.core;

public class Origin {

	private String host;
	private String httpPort;
	private String httpsPort;
	
		
	public Origin(String host, String httpPort, String httpsPort) {
		super();
		this.host = host;
		this.httpPort = httpPort;
		this.httpsPort = httpsPort;
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

	@Override
	public String toString() {
		return "Origin [host=" + host + ", httpPort=" + httpPort + ", httpsPort=" + httpsPort + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((host == null) ? 0 : host.hashCode());
		result = prime * result + ((httpPort == null) ? 0 : httpPort.hashCode());
		result = prime * result + ((httpsPort == null) ? 0 : httpsPort.hashCode());
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
		Origin other = (Origin) obj;
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
		return true;
	}
	
	
}
