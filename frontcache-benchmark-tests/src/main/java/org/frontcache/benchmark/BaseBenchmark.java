package org.frontcache.benchmark;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;


@Fork(5)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class BaseBenchmark {
	


    private static Random r = new Random();
    
    protected String getRandomUrl() {
        return random(getfileMapping().keySet());
    }

    protected Map<String, String> getfileMapping(){
    	Map<String, String> map = new HashMap<>();
    	
    	map.put("https://www.coinshome.net/en/welcome.htm", "welcome.html");
    	
    	return map;
    	
    }
    
    public static <T> T random(Collection<T> items){
        return ((T[])items.toArray())[r.nextInt(items.size())];
    }
    
}
