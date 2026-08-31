package com.banktagfolders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Turns the claims this plugin makes about itself into something the build
 * checks.
 * <p>
 * The Plugin Hub reviews two things: that a plugin is not malicious, and that
 * it stays inside Jagex's third-party client guidelines. Both are properties of
 * the source, so both can be asserted against the source. Every rule below
 * corresponds to a statement in the README; if someone later writes code that
 * contradicts one, the build fails rather than the claim quietly going stale.
 * <p>
 * Scanning is done with comments stripped, so prose explaining a rule is never
 * mistaken for a breach of it.
 */
public class ComplianceTest
{
	private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

	/**
	 * The only call in this plugin that reaches the server, pinned to exactly
	 * one occurrence so that adding another is a build failure rather than a
	 * judgement made in passing.
	 * <p>
	 * It closes the potion store when a tag is opened while the store is up, and
	 * is a direct copy of what the core Bank Tags plugin does at that moment.
	 * See ColumnActions#openTag.
	 */
	private static final int ALLOWED_MENU_ACTIONS = 1;

	private Map<String, String> sources()
	{
		try (Stream<Path> files = Files.walk(SOURCE_ROOT))
		{
			Map<String, String> out = new LinkedHashMap<>();
			for (Path p : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList()))
			{
				out.put(p.getFileName().toString(), stripComments(
					new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
			}
			assertTrue("no sources found under " + SOURCE_ROOT, !out.isEmpty());
			return out;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** Remove block and line comments so documentation cannot trip a rule. */
	private static String stripComments(String src)
	{
		return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	private void forbid(String why, String... patterns)
	{
		List<String> hits = new ArrayList<>();
		sources().forEach((name, body) ->
		{
			for (String p : patterns)
			{
				if (body.contains(p))
				{
					hits.add(name + " contains " + p);
				}
			}
		});
		assertEquals(why + " " + hits, 0, hits.size());
	}

	// ------------------------------------------------------------------
	// Plugin Hub rules
	// ------------------------------------------------------------------

	@Test
	public void usesNoReflection()
	{
		forbid("reflection is not permitted in hub plugins",
			"java.lang.reflect", "Class.forName", "setAccessible",
			"getDeclaredField", "getDeclaredMethod", "getDeclaredConstructor",
			"MethodHandles", "TypeToken");
	}

	/**
	 * The hub rejects plugins that build their own Gson: the client's instance
	 * is injectable and carries its serialisation settings, so a private one
	 * both duplicates it and drifts from it.
	 */
	@Test
	public void usesTheClientsGson()
	{
		forbid("inject the client's Gson rather than constructing one", "new Gson(");
	}

	@Test
	public void loadsNoCodeAndSpawnsNoProcesses()
	{
		forbid("no runtime code loading, native code or external processes",
			"ProcessBuilder", "Runtime.getRuntime", "System.load", "System.loadLibrary",
			"URLClassLoader", "defineClass", "ScriptEngine");
	}

	@Test
	public void touchesNoFilesystem()
	{
		forbid("everything is stored through RuneLite's ConfigManager",
			"java.io.File", "java.nio.file", "FileInputStream", "FileOutputStream",
			"FileReader", "FileWriter", "RandomAccessFile");
	}

	/** No third-party server, which is why no data-sharing warning is needed. */
	@Test
	public void makesNoNetworkCalls()
	{
		forbid("the plugin sends no data anywhere",
			"java.net.", "HttpURLConnection", "okhttp", "OkHttp", "Socket",
			"http://", "https://");
	}

	@Test
	public void addsNoThirdPartyDependencies() throws IOException
	{
		String build = new String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8);
		List<String> offenders = new ArrayList<>();
		for (String line : build.split("\n"))
		{
			String t = line.trim();
			boolean declares = t.startsWith("implementation")
				|| t.startsWith("compileOnly")
				|| t.startsWith("annotationProcessor")
				|| t.startsWith("testImplementation");
			// Anything not from RuneLite, Lombok or JUnit has to be verified by
			// hand by a maintainer before the plugin can be accepted.
			if (declares && !(t.contains("net.runelite") || t.contains("lombok") || t.contains("junit")))
			{
				offenders.add(t);
			}
		}
		assertEquals("unexpected dependency " + offenders, 0, offenders.size());
	}

	// ------------------------------------------------------------------
	// Jagex third-party client guidelines
	// ------------------------------------------------------------------

	@Test
	public void addsNoMenuEntries()
	{
		forbid("menu entries that send actions to the server are not allowed",
			"createMenuEntry", "setMenuEntries", "insertMenuItem");
	}

	/**
	 * The plugin invokes exactly one existing interface op and no more. A second
	 * one appearing is a decision that deserves review, not a silent addition.
	 */
	@Test
	public void invokesOnlyTheDocumentedServerAction()
	{
		int found = 0;
		for (String body : sources().values())
		{
			int i = body.indexOf("menuAction(");
			while (i != -1)
			{
				found++;
				i = body.indexOf("menuAction(", i + 1);
			}
		}
		assertEquals("unexpected number of server-bound menu actions", ALLOWED_MENU_ACTIONS, found);
	}

	/**
	 * Jagex protects the click zones of the 3D scene, inventory, worn equipment,
	 * spellbook and prayer book. This plugin only ever addresses the bank, so
	 * the simplest way to show it stays clear of those is to require that the
	 * bank is the only interface it names.
	 */
	@Test
	public void addressesOnlyTheBankInterface()
	{
		List<String> offenders = new ArrayList<>();
		sources().forEach((name, body) ->
		{
			Matcher m = Pattern.compile("InterfaceID\\.([A-Za-z_]+)").matcher(body);
			while (m.find())
			{
				if (!m.group(1).equalsIgnoreCase("BANKMAIN"))
				{
					offenders.add(name + " uses InterfaceID." + m.group(1));
				}
			}
		});
		assertEquals("only the bank may be addressed " + offenders, 0, offenders.size());
	}

	// ------------------------------------------------------------------
	// Threading and wiring
	// ------------------------------------------------------------------

	/**
	 * Calling into Client from an AWT thread throws, and the exception is
	 * swallowed, so the feature silently does nothing. Nothing here is reachable
	 * from AWT today; this keeps it that way.
	 */
	@Test
	public void keepsClientCallsOffAwtThreads()
	{
		List<String> offenders = new ArrayList<>();
		sources().forEach((name, body) ->
		{
			boolean awtFacing = body.contains("javax.swing")
				|| body.contains("java.awt.event")
				|| body.contains("MouseListener")
				|| body.contains("KeyListener")
				|| body.contains("HotkeyListener");
			if (awtFacing && body.contains("net.runelite.api.Client"))
			{
				offenders.add(name);
			}
		});
		assertEquals("AWT-facing classes must reach the client via ClientThread " + offenders,
			0, offenders.size());
	}

	/**
	 * State injected in more than one place has to be a singleton, or each
	 * injection point silently gets its own copy and the wiring does nothing.
	 * The plugin class is excluded: RuneLite manages its lifetime.
	 */
	@Test
	public void sharedStateIsSingleton()
	{
		List<String> offenders = new ArrayList<>();
		sources().forEach((name, body) ->
		{
			if (name.equals("BankTagFoldersPlugin.java") || !body.contains("@Inject"))
			{
				return;
			}
			if (!body.contains("@Singleton"))
			{
				offenders.add(name);
			}
		});
		assertEquals("injected collaborators must be @Singleton " + offenders, 0, offenders.size());
	}
}
