package org.frontcache.core;

import java.io.Serializable;


/**
 * A simple class that holds a pair of values.
 * This may be useful for methods that care to
 * return two values (instead of just one).
 */
public class Pair<E1,E2> implements Serializable {

    private static final long serialVersionUID = 2L;

    private E1 mFirst;
    private E2 mSecond;

    public Pair(E1 first, E2 second) {
        mFirst = first;
        mSecond = second;
    }

    public E1 first() {
        return mFirst;
    }

    public E2 second() {
        return mSecond;
    }

    public void setFirst(E1 first) {
        mFirst = first;
    }

    public void setSecond(E2 second) {
        mSecond = second;
    }

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((mFirst == null) ? 0 : mFirst.hashCode());
		result = prime * result + ((mSecond == null) ? 0 : mSecond.hashCode());
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
		Pair other = (Pair) obj;
		if (mFirst == null) {
			if (other.mFirst != null)
				return false;
		} else if (!mFirst.equals(other.mFirst))
			return false;
		if (mSecond == null) {
			if (other.mSecond != null)
				return false;
		} else if (!mSecond.equals(other.mSecond))
			return false;
		return true;
	}

    

} // Pair
