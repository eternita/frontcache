package org.frontcache.console.service;

public class HystrixConnection {

	private String name;
	private String stream;
	private String auth = "";
	private String delay = "";
	
	public HystrixConnection() {
		// TODO Auto-generated constructor stub
	}

	public HystrixConnection(String name, String stream) {
		super();
		this.name = name;
		this.stream = stream;
	}

	public HystrixConnection(String name, String stream, String auth, String delay) {
		super();
		this.name = name;
		this.stream = stream;
		this.auth = auth;
		this.delay = delay;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getStream() {
		return stream;
	}

	public void setStream(String stream) {
		this.stream = stream;
	}

	public String getAuth() {
		return auth;
	}

	public void setAuth(String auth) {
		this.auth = auth;
	}

	public String getDelay() {
		return delay;
	}

	public void setDelay(String delay) {
		this.delay = delay;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((stream == null) ? 0 : stream.hashCode());
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
		HystrixConnection other = (HystrixConnection) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (stream == null) {
			if (other.stream != null)
				return false;
		} else if (!stream.equals(other.stream))
			return false;
		return true;
	}
	
	
	
}
