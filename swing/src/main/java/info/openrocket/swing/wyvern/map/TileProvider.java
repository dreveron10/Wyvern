package info.openrocket.swing.wyvern.map;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.openrocket.core.arch.SystemInfo;
import info.openrocket.core.util.BuildProperties;

/**
 * Loads slippy map tiles in the background and caches them in memory and on disk.
 * <p>
 * The default source is the OpenStreetMap tile server. Its usage policy asks clients to
 * identify themselves, cache tiles and keep parallel requests low, which is why this
 * class sends a Wyvern user agent, keeps tiles on disk for 30 days and downloads with
 * two threads. The source can be changed with the {@code wyvern.map.tileUrl} system
 * property, using {z}, {x} and {y} placeholders.
 */
public class TileProvider {
	private static final Logger log = LoggerFactory.getLogger(TileProvider.class);

	public static final String DEFAULT_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
	public static final String ATTRIBUTION = "© OpenStreetMap contributors";

	private static final int MEMORY_TILES = 300;
	private static final long MAX_AGE_MILLIS = 30L * 24 * 3600 * 1000;
	private static final long RETRY_MILLIS = 30_000;

	private final String urlTemplate;
	private final File cacheDir;
	private final Map<String, BufferedImage> memory = Collections.synchronizedMap(
			new LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
					return size() > MEMORY_TILES;
				}
			});
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	private final Map<String, Long> failed = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "Wyvern-map-tiles");
		thread.setDaemon(true);
		return thread;
	});

	public TileProvider() {
		this(System.getProperty("wyvern.map.tileUrl", DEFAULT_URL),
				new File(SystemInfo.getUserApplicationDirectory(), "wyvern-map-tiles"));
	}

	public TileProvider(String urlTemplate, File cacheDir) {
		this.urlTemplate = urlTemplate;
		this.cacheDir = cacheDir;
	}

	/** Whether tiles come from the OpenStreetMap server, which requires its attribution on the map. */
	public boolean isOpenStreetMap() {
		return urlTemplate.contains("openstreetmap.org");
	}

	/**
	 * Return a tile if it is in memory, otherwise start loading it.
	 *
	 * @param onLoaded run on the event dispatch thread once the tile has arrived
	 * @return the tile image, or null while it is loading or unavailable
	 */
	public BufferedImage getTile(int zoom, int x, int y, Runnable onLoaded) {
		int n = 1 << zoom;
		if (y < 0 || y >= n) {
			return null;
		}
		int wrappedX = ((x % n) + n) % n;
		String key = zoom + "/" + wrappedX + "/" + y;
		BufferedImage image = memory.get(key);
		if (image != null) {
			return image;
		}
		Long failedAt = failed.get(key);
		if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MILLIS) {
			return null;
		}
		if (pending.add(key)) {
			executor.execute(() -> {
				try {
					BufferedImage loaded = load(zoom, wrappedX, y);
					if (loaded != null) {
						memory.put(key, loaded);
						failed.remove(key);
						SwingUtilities.invokeLater(onLoaded);
					} else {
						failed.put(key, System.currentTimeMillis());
					}
				} finally {
					pending.remove(key);
				}
			});
		}
		return null;
	}

	/**
	 * Download tiles to the disk cache so they are available without a connection.
	 * Tiles already cached and fresh are not downloaded again.
	 *
	 * @param tileCoordinates {zoom, x, y} triples
	 * @param onProgress      called on the event dispatch thread after each tile with
	 *                        {done, available}, where available counts tiles now on disk
	 */
	public void prefetch(java.util.List<int[]> tileCoordinates, java.util.function.Consumer<int[]> onProgress) {
		java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger available = new java.util.concurrent.atomic.AtomicInteger();
		for (int[] t : tileCoordinates) {
			executor.execute(() -> {
				BufferedImage image = load(t[0], t[1], t[2]);
				if (image != null) {
					memory.put(t[0] + "/" + t[1] + "/" + t[2], image);
					available.incrementAndGet();
				}
				int[] progress = { done.incrementAndGet(), available.get() };
				SwingUtilities.invokeLater(() -> onProgress.accept(progress));
			});
		}
	}

	private BufferedImage load(int zoom, int x, int y) {
		File file = new File(cacheDir, zoom + File.separator + x + File.separator + y + ".png");
		boolean cached = file.isFile();
		boolean fresh = cached && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MILLIS;
		if (!fresh) {
			try {
				download(zoom, x, y, file);
				cached = true;
			} catch (IOException e) {
				log.debug("Map tile {}/{}/{} could not be downloaded: {}", zoom, x, y, e.toString());
			}
		}
		if (!cached) {
			return null;
		}
		try {
			return ImageIO.read(file);
		} catch (IOException e) {
			log.debug("Cached map tile {} could not be read: {}", file, e.toString());
			return null;
		}
	}

	private void download(int zoom, int x, int y, File file) throws IOException {
		String url = urlTemplate.replace("{z}", String.valueOf(zoom)).replace("{x}", String.valueOf(x))
				.replace("{y}", String.valueOf(y));
		URLConnection connection = URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(10_000);
		connection.setReadTimeout(15_000);
		connection.setRequestProperty("User-Agent",
				"Wyvern/" + BuildProperties.getWyvernVersion() + " (+https://github.com/dreveron10/wyvern)");
		File parent = file.getParentFile();
		if (!parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Cannot create tile cache folder " + parent);
		}
		File temp = new File(parent, file.getName() + ".part");
		try (InputStream in = connection.getInputStream()) {
			Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}
}
