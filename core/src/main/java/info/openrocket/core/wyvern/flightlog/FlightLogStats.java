package info.openrocket.core.wyvern.flightlog;

import info.openrocket.core.wyvern.flightlog.FlightLog.EventType;

/**
 * The numbers that describe a flight, worked out the same way for a recorded log and for
 * a simulation so the two can be compared line by line.
 * <p>
 * All values are SI. A value that cannot be determined from the data is NaN; for
 * example the main descent rate of a log that stops recording before the main opens.
 *
 * @param apogee           highest altitude above the launch site in m
 * @param apogeeTime       time of the highest altitude in s
 * @param maxVelocity      highest upward velocity in m/s, logged or derived from altitude
 * @param maxVelocityTime  time of the highest upward velocity in s
 * @param mainTime         time the main deployed in s: the device's mark, or the time the
 *                         descent rate changes if the device marked nothing
 * @param mainAltitude     altitude at {@code mainTime} in m
 * @param drogueRate       descent rate between apogee and main deployment in m/s, positive down
 * @param mainRate         descent rate between main deployment and landing in m/s, positive down
 * @param landingTime      time of landing in s
 * @param descentTime      time from apogee to landing in s
 */
public record FlightLogStats(double apogee, double apogeeTime, double maxVelocity, double maxVelocityTime,
		double mainTime, double mainAltitude, double drogueRate, double mainRate, double landingTime,
		double descentTime) {

	/** Time after a deployment before the descent rate is taken as settled, in s. */
	static final double DROGUE_SETTLE = 2.0;
	static final double MAIN_SETTLE = 3.0;
	/** Altitude band around the final altitude that counts as "on the ground", in m. */
	static final double GROUND_BAND = 3.0;
	/** Data that ends within this of its starting altitude, in m, is taken to end at touchdown. */
	static final double LAUNCH_LEVEL_BAND = 10.0;
	/** Shortest stretch of data a descent rate is fitted to, in s. */
	static final double MIN_FIT_SPAN = 1.5;
	/** The two descent rates must differ by this factor for a rate change to count as a main deployment. */
	static final double RATE_CHANGE = 1.5;

	/** Work out the statistics of a flight. */
	public static FlightLogStats of(FlightLog log) {
		int n = log.size();
		int apogeeIndex = 0;
		for (int i = 1; i < n; i++) {
			if (log.getAltitude(i) > log.getAltitude(apogeeIndex)) {
				apogeeIndex = i;
			}
		}
		double apogee = log.getAltitude(apogeeIndex);
		double apogeeTime = log.getTime(apogeeIndex);

		double maxVelocity = Double.NaN;
		double maxVelocityTime = Double.NaN;
		double[] velocity = velocity(log);
		for (int i = 0; i <= apogeeIndex; i++) {
			if (!Double.isNaN(velocity[i]) && (Double.isNaN(maxVelocity) || velocity[i] > maxVelocity)) {
				maxVelocity = velocity[i];
				maxVelocityTime = log.getTime(i);
			}
		}

		double landingTime = landingTime(log, apogeeIndex);
		// Descent data ends at landing, or where the recording stops or breaks off
		double descentEnd = Double.isNaN(landingTime) ? endOfContinuousData(log, apogeeIndex) : landingTime;

		double drogueTime = log.eventTime(EventType.DROGUE);
		double descentStart = Math.max(apogeeTime, Double.isNaN(drogueTime) ? apogeeTime : drogueTime) + DROGUE_SETTLE;

		double mainTime = log.eventTime(EventType.MAIN);
		if (!Double.isNaN(mainTime) && (mainTime <= apogeeTime || mainTime > descentEnd)) {
			mainTime = Double.NaN;
		}
		if (Double.isNaN(mainTime)) {
			mainTime = rateChangeTime(log, descentStart, descentEnd);
		}

		double drogueRate;
		double mainRate;
		if (Double.isNaN(mainTime)) {
			// Single deployment, or the main opened where there is no data
			drogueRate = Double.NaN;
			mainRate = Double.isNaN(landingTime) ? Double.NaN : -slope(log, descentStart, descentEnd - 0.5);
			if (Double.isNaN(landingTime)) {
				drogueRate = -slope(log, descentStart, descentEnd);
			}
		} else {
			drogueRate = -slope(log, descentStart, mainTime);
			mainRate = -slope(log, mainTime + MAIN_SETTLE, descentEnd - 0.5);
		}
		double mainAltitude = Double.isNaN(mainTime) ? Double.NaN : log.altitudeAt(mainTime);
		return new FlightLogStats(apogee, apogeeTime, maxVelocity, maxVelocityTime, mainTime, mainAltitude, drogueRate,
				mainRate, landingTime, Double.isNaN(landingTime) ? Double.NaN : landingTime - apogeeTime);
	}

	/**
	 * Vertical velocity of every sample in m/s: the logged value where the device has one,
	 * otherwise the slope of a line fitted to the altitude within 0.25 s either side.
	 */
	public static double[] velocity(FlightLog log) {
		int n = log.size();
		double[] v = new double[n];
		if (log.hasVelocity()) {
			for (int i = 0; i < n; i++) {
				v[i] = log.getVelocity(i);
			}
			return v;
		}
		int lo = 0;
		int hi = 0;
		for (int i = 0; i < n; i++) {
			double t = log.getTime(i);
			while (log.getTime(lo) < t - 0.25) {
				lo++;
			}
			while (hi + 1 < n && log.getTime(hi + 1) <= t + 0.25) {
				hi++;
			}
			if (lo == hi) {
				// Sparse data: fall back to the neighbouring samples
				int a = Math.max(0, i - 1);
				int b = Math.min(n - 1, i + 1);
				double span = log.getTime(b) - log.getTime(a);
				v[i] = span > 0 && span <= 2.0 ? (log.getAltitude(b) - log.getAltitude(a)) / span : Double.NaN;
			} else {
				v[i] = slope(log, lo, hi);
			}
		}
		return v;
	}

	/**
	 * Landing: the first time after apogee from which the altitude stays within the ground
	 * band of its final value, or the end of data that stops at launch level. NaN if the
	 * rocket was still in the air when the recording ended, or if the landing falls inside
	 * a gap in the data.
	 */
	private static double landingTime(FlightLog log, int apogeeIndex) {
		int n = log.size();
		double finalAltitude = log.getAltitude(n - 1);
		int lastMoving = -1;
		for (int i = n - 1; i >= apogeeIndex; i--) {
			if (Math.abs(log.getAltitude(i) - finalAltitude) > GROUND_BAND) {
				lastMoving = i;
				break;
			}
		}
		if (lastMoving < 0) {
			return Double.NaN;
		}
		// The rocket must be seen at rest for a while, and the touchdown itself must be recorded
		if (lastMoving >= n - 2 || log.getTime(n - 1) - log.getTime(lastMoving + 1) < 1.0) {
			// A simulation stops at ground hit: accept data that ends back at launch level
			boolean endsAtLaunchLevel = n - 1 > apogeeIndex
					&& Math.abs(finalAltitude - log.getAltitude(0)) <= LAUNCH_LEVEL_BAND;
			return endsAtLaunchLevel ? log.getTime(n - 1) : Double.NaN;
		}
		for (double[] gap : log.gaps()) {
			if (gap[0] <= log.getTime(lastMoving) && log.getTime(lastMoving) < gap[1]) {
				return Double.NaN;
			}
		}
		return log.getTime(lastMoving + 1);
	}

	/** Time of the last sample after apogee before the recording stops or breaks off. */
	private static double endOfContinuousData(FlightLog log, int apogeeIndex) {
		double apogeeTime = log.getTime(apogeeIndex);
		for (double[] gap : log.gaps()) {
			if (gap[0] >= apogeeTime) {
				return gap[0];
			}
		}
		return log.getTime(log.size() - 1);
	}

	/**
	 * Find a main deployment the device did not mark: the time that best splits the
	 * descent into two straight lines. Only accepted if the descent slows by a clear
	 * factor at the split; otherwise NaN.
	 */
	private static double rateChangeTime(FlightLog log, double start, double end) {
		int first = firstIndexAtOrAfter(log, start);
		int last = lastIndexAtOrBefore(log, end);
		if (first < 0 || last - first < 8) {
			return Double.NaN;
		}
		int count = last - first + 1;
		// Prefix sums of t, h, t*t, t*h relative to the first sample, for O(1) line fits
		double t0 = log.getTime(first);
		double[] st = new double[count + 1];
		double[] sh = new double[count + 1];
		double[] stt = new double[count + 1];
		double[] sth = new double[count + 1];
		double[] shh = new double[count + 1];
		for (int i = 0; i < count; i++) {
			double t = log.getTime(first + i) - t0;
			double h = log.getAltitude(first + i);
			st[i + 1] = st[i] + t;
			sh[i + 1] = sh[i] + h;
			stt[i + 1] = stt[i] + t * t;
			sth[i + 1] = sth[i] + t * h;
			shh[i + 1] = shh[i] + h * h;
		}
		double best = Double.POSITIVE_INFINITY;
		int bestSplit = -1;
		double[] bestSlopes = null;
		for (int split = 4; split <= count - 4; split++) {
			double[] a = fit(st, sh, stt, sth, shh, 0, split);
			double[] b = fit(st, sh, stt, sth, shh, split, count);
			double residual = a[1] + b[1];
			if (residual < best) {
				best = residual;
				bestSplit = split;
				bestSlopes = new double[] { a[0], b[0] };
			}
		}
		if (bestSplit < 0) {
			return Double.NaN;
		}
		double before = -bestSlopes[0];
		double after = -bestSlopes[1];
		if (after <= 0 || before < RATE_CHANGE * after) {
			return Double.NaN;
		}
		return log.getTime(first + bestSplit);
	}

	/** Least-squares line over prefix-sum range [from, to): returns {slope, sum of squared residuals}. */
	private static double[] fit(double[] st, double[] sh, double[] stt, double[] sth, double[] shh, int from, int to) {
		double n = to - from;
		double t = st[to] - st[from];
		double h = sh[to] - sh[from];
		double tt = stt[to] - stt[from];
		double th = sth[to] - sth[from];
		double hh = shh[to] - shh[from];
		double varT = tt - t * t / n;
		double cov = th - t * h / n;
		double varH = hh - h * h / n;
		if (varT <= 0) {
			return new double[] { 0, Math.max(0, varH) };
		}
		double slope = cov / varT;
		return new double[] { slope, Math.max(0, varH - slope * cov) };
	}

	/** Slope in m/s of a line fitted to the altitude between two times; NaN if there is too little data. */
	static double slope(FlightLog log, double start, double end) {
		int first = firstIndexAtOrAfter(log, start);
		int last = lastIndexAtOrBefore(log, end);
		if (first < 0 || last <= first || log.getTime(last) - log.getTime(first) < MIN_FIT_SPAN) {
			return Double.NaN;
		}
		return slope(log, first, last);
	}

	private static double slope(FlightLog log, int first, int last) {
		double n = last - first + 1;
		double t0 = log.getTime(first);
		double st = 0;
		double sh = 0;
		double stt = 0;
		double sth = 0;
		for (int i = first; i <= last; i++) {
			double t = log.getTime(i) - t0;
			double h = log.getAltitude(i);
			st += t;
			sh += h;
			stt += t * t;
			sth += t * h;
		}
		double varT = stt - st * st / n;
		return varT <= 0 ? Double.NaN : (sth - st * sh / n) / varT;
	}

	private static int firstIndexAtOrAfter(FlightLog log, double t) {
		for (int i = 0; i < log.size(); i++) {
			if (log.getTime(i) >= t) {
				return i;
			}
		}
		return -1;
	}

	private static int lastIndexAtOrBefore(FlightLog log, double t) {
		for (int i = log.size() - 1; i >= 0; i--) {
			if (log.getTime(i) <= t) {
				return i;
			}
		}
		return -1;
	}
}
