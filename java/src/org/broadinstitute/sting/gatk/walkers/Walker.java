package org.broadinstitute.sting.gatk.walkers;

import java.io.PrintStream;
import java.util.List;

import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.Pair;
import org.apache.log4j.Logger;

/**
 * Created by IntelliJ IDEA.
 * User: hanna
 * Date: Mar 17, 2009
 * Time: 1:53:31 PM
 * To change this template use File | Settings | File Templates.
 */
public abstract class Walker<MapType, ReduceType> {
    // TODO: Can a walker be templatized so that map and reduce live here?

    protected static Logger logger = Logger.getLogger(Walker.class);

    /**
     * A stream for writing normal (non-error) output.  System.out by default.
     */
    protected PrintStream out = null;

    /**
     * A stream for writing error output.  System.err by default.
     */
    protected PrintStream err = null;

    protected Walker() {
    }

    /**
     * Retrieve the toolkit, for peering into internal structures that can't
     * otherwise be read.  Use sparingly, and discuss uses with software engineering
     * team.
     * @return The genome analysis toolkit.
     */
    protected GenomeAnalysisEngine getToolkit() {
        return GenomeAnalysisEngine.instance;
    }

    /**
     * (conceptual static) method that states whether you want to see reads piling up at a locus
     * that contain a deletion at the locus.
     *
     * ref:   ATCTGA
     * read1: ATCTGA
     * read2: AT--GA
     *
     * Normally, the locus iterator only returns a list of read1 at this locus at position 3, but
     * if this function returns true, then the system will return (read1, read2) with offsets
     * of (3, -1).  The -1 offset indicates a deletion in the read.
     *
     * @return false if you don't want to see deletions, or true if you do
     */
    public boolean includeReadsWithDeletionAtLoci() { 
        return false;
    }

    /**
     * This method states whether you want to see pileups of "extended events" (currently, indels only)
     * at every locus that has at least one indel associated with it. Consider the following situation:
     *
     * ref:    AT--CTGA  (note that we expanded the ref here with -- to accomodate insertion in read3)
     * read1:  AT--CTGA  (perfectly matches the ref)
     * read2:  AT----GA  (deletion -CT w.r.t. the ref)
     * read3:  ATGGCTGA  (insertion +GG w.r.t the ref)
     *
     * Normally, the locus iterator only returns read base pileups over reference bases, optionally with deleted bases
     * included (see #includeReadsWithDeletionAtLoci()). In other words, the pileup over the second reference base (T)
     * will be [T,T,T] (all reads count), for the next reference base (C) the pileup will be [C,C] (or [C,-,C] if
     * #includeReadsWithDeletionAtLoci() is true), next pileup generated over the next reference
     * base (T) will be either [T,T], or [T,'-',T], etc. In this default mode, a) insertions are not seen by a walker at all, and
     * b) deletions are (optionally) seen only on a base-by-base basis (as the step-by-step traversal over the reference
     * bases is performed). In the extended event mode, however, if there is at least one indel associated with a reference
     * locus, the engine will generate an <i>additional</i> call to the walker's map() method, with a pileup of
     * full-length extended indel/noevent calls. This call will be made <i>after</i> the conventional base pileup call
     * at that locus. Thus, in the example above, a conventional call will be first made at the second reference base (T),
     * with the [T,T,T] pileup of read bases, then an extended event call will be made at the <i>same</i> locus with
     * pileup [no_event, -CT, +GG] (i.e. extended events associated with that reference base). After that, the traversal
     * engine will move to the next reference base.
     *
     * @return false if you do not want to receive extra pileups with extended events, or true if you do.
     */
    public boolean generateExtendedEvents() {
        return false;
    }

    public void initialize() { }

    /**
     * Provide an initial value for reduce computations.
     * @return Initial value of reduce.
     */
    public abstract ReduceType reduceInit();

    /**
     * Reduces a single map with the accumulator provided as the ReduceType.
     * @param value result of the map.
     * @param sum accumulator for the reduce.
     * @return accumulator with result of the map taken into account.
     */
    public abstract ReduceType reduce(MapType value, ReduceType sum);    

    public void onTraversalDone(ReduceType result) {
        out.println("[REDUCE RESULT] Traversal result is: " + result);
    }

    /**
     * General interval reduce routine called after all of the traversals are done
     * @param results
     */
    public void onTraversalDone(List<Pair<GenomeLoc, ReduceType>> results) {
        for ( Pair<GenomeLoc, ReduceType> result : results ) {
            out.printf("[INTERVAL REDUCE RESULT] at %s ", result.getFirst());
            this.onTraversalDone(result.getSecond());
        }
    }

    /**
     * Return true if your walker wants to reduce each interval separately.  Default is false.
     *
     * If you set this flag, several things will happen.
     *
     * The system will invoke reduceInit() once for each interval being processed, starting a fresh reduce
     * Reduce will accumulate normally at each map unit in the interval
     * However, onTraversalDone(reduce) will be called after each interval is processed.
     * The system will call onTraversalDone( GenomeLoc -> reduce ), after all reductions are done,
     *   which is overloaded here to call onTraversalDone(reduce) for each location
     */
    public boolean isReduceByInterval() {
        return false;
    }
}
