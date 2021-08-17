package net.cellar.supp;

public final class DebugUtil {

	/** Determines whether this is a test build. */
	public static final boolean TEST = false;
	
	public static StringBuilder indent(final int level)  {
		return new StringBuilder();
	}

	public static void logBundle(android.os.Bundle bundle) {
	}
	
	public static void logIntent(String tag, android.content.Intent intent, android.net.Uri referrer) {
	}
	
	public static void logIntent(String tag, android.content.Intent intent) {
	}

	private DebugUtil() {
	}
}
