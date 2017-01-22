package org.frontcache.benchmark;

import static org.junit.Assert.assertNotNull;

import java.io.IOException;

import org.junit.BeforeClass;
import org.junit.Test;



public class ExpectedOutputTest {

    @BeforeClass
    public static void beforeClass() {

    }


    @Test
    public void testFrontcacheOutput() throws IOException {
        Frontcache hbs = new Frontcache();
        hbs.setup();
        assertNotNull(hbs.benchmark());
        hbs.tearDown();
        
    }

   
}
