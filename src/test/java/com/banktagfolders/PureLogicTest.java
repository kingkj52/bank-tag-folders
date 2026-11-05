package com.banktagfolders;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PureLogicTest
{
	@Test
	public void aSingleSwapOfNamesIsARename()
	{
		assertArrayEquals(new String[]{"a", "d"},
			FolderTree.renamed(Arrays.asList("a", "b", "c"), Arrays.asList("b", "c", "d")));
	}

	@Test
	public void deletionsAdditionsAndReordersAreNotRenames()
	{
		assertNull(FolderTree.renamed(Arrays.asList("a", "b"), Collections.singletonList("b")));
		assertNull(FolderTree.renamed(Collections.singletonList("a"), Arrays.asList("a", "b")));
		assertNull(FolderTree.renamed(Arrays.asList("a", "b"), Arrays.asList("b", "a")));
		assertNull(FolderTree.renamed(Arrays.asList("a", "b"), Arrays.asList("c", "d")));
	}

	@Test
	public void hexColoursAcceptTheShapesTheMenuPromises()
	{
		assertEquals(Integer.valueOf(0x3A6B8B), ColumnActions.parseHexColour("3a6b8b"));
		assertEquals(Integer.valueOf(0x3A6B8B), ColumnActions.parseHexColour("#3A6B8B"));
		assertEquals(Integer.valueOf(0x33AABB), ColumnActions.parseHexColour(" #3ab "));
		assertNull(ColumnActions.parseHexColour("3a6b8"));
		assertNull(ColumnActions.parseHexColour("zzzzzz"));
		assertNull(ColumnActions.parseHexColour(null));
	}

	@Test
	public void tintStrengthMapsToWidgetOpacityAndIsClamped()
	{
		assertEquals(255, TagColumn.opacityOf(0));
		assertEquals(0, TagColumn.opacityOf(100));
		assertEquals(255, TagColumn.opacityOf(-20));
		assertEquals(0, TagColumn.opacityOf(500));
		assertEquals(255 - 35 * 255 / 100, TagColumn.opacityOf(35));
	}
}
