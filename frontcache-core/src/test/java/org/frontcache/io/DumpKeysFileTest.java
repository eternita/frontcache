/*
 * Copyright 2018 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.frontcache.cache.CacheProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@code dump-keys}' file: written whole under the name the response gave, or not at all - a dump that
 * fails part way must not leave a truncated list that reads as a complete one.
 */
public class DumpKeysFileTest {

	@Rule
	public TemporaryFolder home = new TemporaryFolder();

	/** A processor whose listing hands over {@code keys}, then throws {@code failure} if one is given. */
	@SuppressWarnings("unchecked")
	private static CacheProcessor listing(List<String> keys, IOException failure) throws IOException {
		CacheProcessor processor = mock(CacheProcessor.class);
		when(processor.forEachCachedKey(any(), any(), anyInt(), any(Consumer.class))).thenAnswer(invocation -> {
			Consumer<String> sink = invocation.getArgument(3);
			keys.forEach(sink);
			if (null != failure)
				throw failure;
			return (long) keys.size();
		});
		return processor;
	}

	@Test
	public void everyKeyIsWrittenOnePerLineInUtf8AndNoTempFileIsLeft() throws Exception {
		List<String> keys = Arrays.asList("/a.html", "/ünï/çødé.html", "http://l1.example.com/b.html");
		File target = new File(home.getRoot(), "warmer/keys_1.txt"); // warmer/ does not exist yet

		long written = FrontCacheIOServlet.writeKeysFile(listing(keys, null), null, target);

		assertEquals(3, written);
		assertEquals("/a.html\n/ünï/çødé.html\nhttp://l1.example.com/b.html\n",
				new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
		assertFalse(new File(target.getPath() + ".tmp").exists());
	}

	@Test
	public void aListingThatFailsPartWayLeavesNoFileAtAll() throws Exception {
		File target = new File(home.getRoot(), "warmer/keys_2.txt");
		CacheProcessor processor = listing(Arrays.asList("/a.html", "/b.html"), new IOException("L2 index unavailable"));

		IOException thrown = assertThrows(IOException.class, () -> FrontCacheIOServlet.writeKeysFile(processor, null, target));

		assertEquals("L2 index unavailable", thrown.getMessage());
		assertFalse("a partial dump must not appear under the dump's name", target.exists());
		assertFalse("the partial temp file must be removed", new File(target.getPath() + ".tmp").exists());
	}

	@Test
	public void anEmptyCacheIsAnEmptyFile() throws Exception {
		File target = new File(home.getRoot(), "warmer/keys_3.txt");

		assertEquals(0, FrontCacheIOServlet.writeKeysFile(listing(Arrays.<String>asList(), null), null, target));
		assertEquals(0, target.length());
	}

	@Test
	public void aPrefixIsPassedToTheListing() throws Exception {
		CacheProcessor processor = listing(Arrays.asList("/p/a.html"), null);

		FrontCacheIOServlet.writeKeysFile(processor, "/p/", new File(home.getRoot(), "warmer/keys_4.txt"));

		verify(processor).forEachCachedKey(eq("/p/"), eq(null), eq(Integer.MAX_VALUE), any());
	}
}
