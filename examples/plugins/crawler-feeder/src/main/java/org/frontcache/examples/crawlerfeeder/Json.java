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
package org.frontcache.examples.crawlerfeeder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader - objects as {@code Map<String, Object>}, arrays as {@code List<Object>}, strings,
 * numbers as {@code Double}, {@code Boolean}, null. A dropped-in plugin's class loader does not show the node's
 * jackson-databind, and the vendors' files need nothing more than this.
 */
final class Json {

	private static final int MAX_DEPTH = 64;

	private final String text;
	private int pos;

	private Json(String text) {
		this.text = text;
	}

	/** @throws IllegalArgumentException when the text is not one JSON value */
	static Object parse(String text) {
		Json json = new Json(text);
		json.space();
		Object value = json.value(0);
		json.space();
		if (json.pos != text.length())
			throw json.error("trailing content");
		return value;
	}

	private Object value(int depth) {
		if (depth > MAX_DEPTH)
			throw error("nested too deep");
		if (pos >= text.length())
			throw error("unexpected end");
		char c = text.charAt(pos);
		return switch (c) {
			case '{' -> object(depth);
			case '[' -> array(depth);
			case '"' -> string();
			case 't' -> literal("true", Boolean.TRUE);
			case 'f' -> literal("false", Boolean.FALSE);
			case 'n' -> literal("null", null);
			default -> {
				if (c == '-' || (c >= '0' && c <= '9'))
					yield number();
				throw error("unexpected '" + c + "'");
			}
		};
	}

	private Map<String, Object> object(int depth) {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++;
		space();
		if (peek('}'))
		{
			pos++;
			return map;
		}
		while (true)
		{
			space();
			if (!peek('"'))
				throw error("expected a key");
			String key = string();
			space();
			expect(':');
			space();
			map.put(key, value(depth + 1));
			space();
			if (peek(','))
			{
				pos++;
				continue;
			}
			expect('}');
			return map;
		}
	}

	private List<Object> array(int depth) {
		List<Object> list = new ArrayList<>();
		pos++;
		space();
		if (peek(']'))
		{
			pos++;
			return list;
		}
		while (true)
		{
			space();
			list.add(value(depth + 1));
			space();
			if (peek(','))
			{
				pos++;
				continue;
			}
			expect(']');
			return list;
		}
	}

	private String string() {
		pos++;   // the opening quote
		StringBuilder out = new StringBuilder();
		while (true)
		{
			if (pos >= text.length())
				throw error("unterminated string");
			char c = text.charAt(pos++);
			if (c == '"')
				return out.toString();
			if (c < 0x20)
				throw error("a control character in a string");
			if (c != '\\')
			{
				out.append(c);
				continue;
			}
			if (pos >= text.length())
				throw error("unterminated escape");
			char e = text.charAt(pos++);
			switch (e) {
				case '"', '\\', '/' -> out.append(e);
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'u' -> {
					if (pos + 4 > text.length())
						throw error("a short \\u escape");
					try {
						out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
					} catch (NumberFormatException x) {
						throw error("a bad \\u escape");
					}
					pos += 4;
				}
				default -> throw error("a bad escape \\" + e);
			}
		}
	}

	private Double number() {
		int start = pos;
		if (peek('-'))
			pos++;
		digits();
		if (peek('.'))
		{
			pos++;
			digits();
		}
		if (peek('e') || peek('E'))
		{
			pos++;
			if (peek('+') || peek('-'))
				pos++;
			digits();
		}
		try {
			return Double.valueOf(text.substring(start, pos));
		} catch (NumberFormatException e) {
			throw error("a bad number");
		}
	}

	private void digits() {
		int start = pos;
		while (pos < text.length() && Character.isDigit(text.charAt(pos)) && text.charAt(pos) < 128)
			pos++;
		if (start == pos)
			throw error("expected a digit");
	}

	private Object literal(String word, Object value) {
		if (!text.startsWith(word, pos))
			throw error("unexpected token");
		pos += word.length();
		return value;
	}

	private void space() {
		while (pos < text.length())
		{
			char c = text.charAt(pos);
			if (c != ' ' && c != '\t' && c != '\n' && c != '\r')
				return;
			pos++;
		}
	}

	private boolean peek(char c) {
		return pos < text.length() && text.charAt(pos) == c;
	}

	private void expect(char c) {
		if (!peek(c))
			throw error("expected '" + c + "'");
		pos++;
	}

	private IllegalArgumentException error(String what) {
		return new IllegalArgumentException("not JSON: " + what + " at offset " + pos);
	}
}
