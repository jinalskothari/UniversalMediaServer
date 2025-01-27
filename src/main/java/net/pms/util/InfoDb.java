package net.pms.util;

import net.pms.PMS;

import net.pms.PMS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Iterator;
import java.util.Map;

public class InfoDb implements DbHandler {
	public static class InfoDbData {
		public String imdb;
		public String ep_name;
		public String year;
		public String season;
		public String episode;
		public String title;
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(InfoDb.class);
	private static final long REDO_PERIOD = 7 * 24 * 60 * 60 * 1000; // one week
	private static final String LAST_INFO_REREAD_KEY = "lastInfoReread";

	private FileDb db;

	public InfoDb() {
		db = new FileDb(this);
		db.setMinCnt(6);
		db.setUseNullObj(true);
		db.init();
		if (PMS.getKey(LAST_INFO_REREAD_KEY) == null) {
			PMS.setKey(LAST_INFO_REREAD_KEY, "" + System.currentTimeMillis());
		}
		redoNulls();
	}


	private void askAndInsert(File f, String formattedName) {
		try {
			String[] tmp = OpenSubtitle.getInfo(f, formattedName);
			Object obj = db.nullObj();
			if (tmp != null) {
				obj = create(tmp, 0);
			}
			db.add(f.getAbsolutePath(), obj);
		} catch (Exception e) {
			LOGGER.debug("info db ex "+e.toString());
		}
	}

	public void backgroundAdd(final File f, final String formattedName) {
		if (db.get(f.getAbsolutePath()) != null) {
			// we need to use the raw get to see so it's
			// truly null
			// also see if we should redo
			redoNulls();
			return;
		}
		Runnable r = new Runnable() {
			@Override
			public void run() {
				askAndInsert(f, formattedName);
			}
		};
		new Thread(r).start();
	}

	public void moveInfo(File old_file, File new_file) {
		InfoDbData data = get(old_file);
		if (data != null) {
			db.removeNoSync(old_file.getAbsolutePath());
			db.addNoSync(new_file.getAbsolutePath(), data);
			db.sync();
		}
	}

	public InfoDbData get(File f) {
		return get(f.getAbsolutePath());
	}

	public InfoDbData get(String f) {
		Object obj = db.get(f);
		return (InfoDbData) (db.isNull(obj) ? null : obj);
	}

	@Override
	public Object create(String[] args) {
		return create(args, 1);
	}

	public Object create(String[] args, int off) {
		InfoDbData data = new InfoDbData();
		data.imdb = FileDb.safeGetArg(args, off);

		/**
		 * Sometimes if IMDb doesn't have an episode title they call it
		 * something like "Episode #1.4", so discard that.
		 */
		data.ep_name = FileDb.safeGetArg(args, off + 1);
		if (data.ep_name.startsWith("Episode #")) {
			data.ep_name = "";
		}

		data.title = FileDb.safeGetArg(args, off + 2);
		data.season = FileDb.safeGetArg(args, off + 3);
		data.episode = FileDb.safeGetArg(args, off + 4);
		data.year = FileDb.safeGetArg(args, off + 5);

		return data;
	}

	@Override
	public String[] format(Object obj) {
		InfoDbData data = (InfoDbData) obj;
		return new String[]{
			data.imdb,
			data.ep_name,
			data.title,
			data.season,
			data.episode,
			data.year
		};
	}

	@Override
	public String name() {
		return "InfoDb.db";
	}

	private boolean redo() {
		long now = System.currentTimeMillis();
		long last = now;
		try {
			last = Long.parseLong(PMS.getKey(LAST_INFO_REREAD_KEY));
		} catch (NumberFormatException e) {
		}
		return (now - last) > REDO_PERIOD;
	}

	private void redoNulls() {
		if(!db.hasNulls()) // no nulls in db skip this
			return;
		if (!redo() || !PMS.getConfiguration().isInfoDbRetry()) {
			// no redo
			return;
		}
		// update this first to make redo() return false for other
		PMS.setKey(LAST_INFO_REREAD_KEY, "" + System.currentTimeMillis());
		Runnable r = new Runnable() {
			@Override
			public void run() {
				// this whole iterator stuff is to avoid
				// CMEs
				Iterator it = db.iterator();
				boolean sync = false;
				while(it.hasNext()) {
					Map.Entry kv = (Map.Entry) it.next();
					String key = (String) kv.getKey();
					if(!db.isNull(kv.getValue())) // nonNull -> no need to ask again
						continue;
					File f = new File(key);
					String name = f.getName();
					try {
						String[] tmp = OpenSubtitle.getInfo(f, name);
						// if we still get nothing from opensubs
						// we don't fiddle with the db
						if (tmp != null) {
							kv.setValue(create(tmp, 0));
							sync = true;
						}
					} catch (Exception e) {
					}
				}
				if (sync) {
					// we need a manual sync here
					db.sync();
				}
			}
		};
		new Thread(r).start();

	}
}
