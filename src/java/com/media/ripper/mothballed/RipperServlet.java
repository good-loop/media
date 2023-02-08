package com.media.ripper.mothballed;

import java.io.File;

import com.winterwell.utils.ShellScript;
import com.winterwell.utils.Utils;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.io.FileUtils;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.threads.ATask;
import com.winterwell.utils.threads.ATask.QStatus;
import com.winterwell.utils.threads.TaskRunner;
import com.winterwell.utils.time.TUnit;
import com.winterwell.web.WebEx;
import com.winterwell.web.ajax.JSend;
import com.winterwell.web.ajax.KAjaxStatus;
import com.winterwell.web.app.IServlet;
import com.winterwell.web.app.WebRequest;
import com.winterwell.web.fields.SField;

/**
 * Call: /rip?url={}&minview={}&duration={}
 * 
 * or /rip?action=clearall to flush out a jam
 * @author daniel
 *
 */
public class RipperServlet implements IServlet {
	
	private static final SField PARAM_url = new SField("url");
	private static final String LOGTAG = "rip";
	
	private File outDir = new File("web/recordings");

	@Override
	public void process(WebRequest state) throws Exception {
		TaskRunner tr = TaskRunner.getDefault();
		
		// empty the queue - eg a blockage
		if (state.actionIs("clearall")) {
			int cnt = 0;
			for(ATask task : tr.getTodo()) {
				if (task instanceof RipTask) {
					try {
						task.cancel();
					} catch(Throwable ex) {
						Log.w(LOGTAG, ex);
						tr.forget(task);
						cnt++;
					}
				}
			}			
			for(ATask task : tr.getDone()) {
				if (task instanceof RipTask) {
					cnt++;
					tr.forget(task);
				}
			}
			new JSend().setStatus(KAjaxStatus.success).setData(new ArrayMap("forgot",cnt)).send(state);
			return;
		}
		
		// rip!
		String url = state.getRequired(PARAM_url);
		// anti-hack paranoia
		if ( ! FileUtils.isSafe(url)) {
			throw new WebEx.E400("Dodgy url: "+url);
		}

		RipTask rt = new RipTask(outDir, url);
				
//		// previously done?
		// No - allow re-render for modified ads
		File of = rt.getOutputFile();
//		if (of.isFile()) {
//			JSend jsend = new JSend();
//			jsend.setStatus(KAjaxStatus.success);
//			jsend.setData(new ArrayMap(
//				"output", of
//			));
//			jsend.send(state);
//			return;			
//		}
		
		// running?
		ATask already = tr.getTaskMatching(rt);
		
		if (state.actionIs("redo")) {
			Log.i(LOGTAG, "redo "+already);
			// clear out
			FileUtils.delete(of);
			if (already!=null) {
				already.cancel();
				tr.forget(already);
			}
			already = null;
		}
		
		if (already == null && ! state.actionIs("clear")) {
			// no -- do it
			doRecord(state, tr, rt);
			return;
		}
		
		// clear?
		if (state.actionIs("clear")) {
			already.close();
			tr.forget(already);
		}
		
		if (already.isFinished()) {
			// old?			
			JSend jsend = new JSend();
			if (already.getStatus()==QStatus.DONE) {
				// Odd! this should have been caught by the file check higher up!
				Log.w("record", "File check failed for "+already);
				jsend.setStatus(KAjaxStatus.success);
				jsend.setData(new ArrayMap(
					"name", rt.getName(),
					"output", rt.getOutputFile()
				));
			} else {
				jsend.setStatus(KAjaxStatus.error);
				if (already.getError()!=null) jsend.setMessage(already.getError().toString());
			}
			jsend.send(state);
			return;
		}
		
		// in progress
		JSend running = new JSend().setStatus(KAjaxStatus.accepted);
		running.setData(new ArrayMap(
			"name", rt.getName(),
			"runningTime", rt.getRunningTime().getMillisecs()
		));
		running.send(state);
		return;	
	}

	private void doRecord(WebRequest state, TaskRunner tr, RipTask rt) {
		Log.i(LOGTAG, "doRip... "+rt);
		tr.submit(rt);
		JSend jsend = new JSend();
		jsend.setStatus(KAjaxStatus.accepted);
		jsend.setData(new ArrayMap(
			"name", rt.getName()
		));
		jsend.send(state);
	}

}

class RipTask extends ATask<File> {

	private static final String LOGTAG = null;
	private File outDir;
	private String url;

	
	
	@Override
	public String toString() {
		return "RipTask[" + url + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 0;
		result = prime * result + ((url == null) ? 0 : url.hashCode());
		result = prime * result + ((outDir == null) ? 0 : outDir.hashCode());
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
		RipTask other = (RipTask) obj;
		if (url == null) {
			if (other.url != null)
				return false;
		} else if (!url.equals(other.url))
			return false;
		if (outDir == null) {
			if (other.outDir != null)
				return false;
		} else if (!outDir.equals(other.outDir))
			return false;
		return true;
	}

	public RipTask(File outDir, String url) {
		super("RipTask_"+url);
		this.outDir = outDir;
		this.url = url;
	}

	@Override
	protected File run() throws Exception {		
		File outputFile = getOutputFile();
		String ripCommand = "youtube-dl " + url + "--no-color --force-ipv4 --geo-bypass --limit-rate 1M --restrict-filenames --merge-output-format mp4";
		ShellScript proc = new ShellScript(ripCommand);
		Log.i(LOGTAG, "run... "+this+" Command: "+proc.getCommand());
		proc.start();
		Utils.sleep(50); // spew some early stage log output
		Log.i(LOGTAG, "...running... "+this+" Early Output: "+proc.getOutput());
		proc.waitFor(TUnit.HOUR.dt);
		Log.i(LOGTAG, "...done "+this+" Output: "+proc.getOutput());
		return outputFile;
	}
	// Attempting to ask youtube-dl what the formatted output filename will be, and then using the returned output of
	// the youtube-dl --get-filename command to let Java name the output file, and know what it is for later.
	public File getOutputFile() {
		File outputFile = getOutputFile();
		String formattedFilename = "youtube-dl --restrict-filenames " + url + "--get-filename";
		ShellScript proc = new ShellScript(formattedFilename);
		return new File(outDir, FileUtils.safeFilename(formattedFilename+".mp4"));
	}
	
}