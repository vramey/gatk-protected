package org.broadinstitute.sting.playground.gatk.walkers.variants;

import org.broadinstitute.sting.gatk.LocusContext;
import org.broadinstitute.sting.gatk.refdata.*;
import org.broadinstitute.sting.gatk.walkers.DataSource;
import org.broadinstitute.sting.gatk.walkers.LocusWalker;
import org.broadinstitute.sting.gatk.walkers.RMD;
import org.broadinstitute.sting.gatk.walkers.Requires;
import org.broadinstitute.sting.playground.utils.AlleleFrequencyEstimate;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.PackageUtils;
import org.broadinstitute.sting.utils.StingException;
import org.broadinstitute.sting.utils.cmdLine.Argument;
import org.broadinstitute.sting.playground.gatk.walkers.variants.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;

import net.sf.samtools.SAMRecord;

/**
 * VariantFiltrationWalker applies specified conditionally independent features and filters to pre-called variants.
 * The former modifiesthe likelihoods of each genotype, while the latter makes a decision to include or exclude a
 * variant outright.  At the moment, the variants are expected to be in gelitext format.
 */
@Requires(value={DataSource.READS, DataSource.REFERENCE},referenceMetaData=@RMD(name="variant",type=rodVariants.class))
public class VariantFiltrationWalker extends LocusWalker<Integer, Integer> {
    @Argument(fullName="variants_out_head", shortName="VOH", doc="File to which modified variants should be written") public String VARIANTS_OUT_HEAD;
    @Argument(fullName="features", shortName="F", doc="Feature test (optionally with arguments) to apply to genotype posteriors.  Syntax: 'testname[:arguments]'", required=false) public String[] FEATURES;
    @Argument(fullName="exclusion_criterion", shortName="X", doc="Exclusion test (optionally with arguments) to apply to variant call.  Syntax: 'testname[:arguments]'", required=false) public String[] EXCLUSIONS;
    @Argument(fullName="verbose", shortName="V", doc="Show how the variant likelihoods are changing with the application of each feature") public Boolean VERBOSE = false;
    @Argument(fullName="list", shortName="ls", doc="List the available features and exclusion criteria and exit") public Boolean LIST = false;
    @Argument(fullName="learning_mode", shortName="LM", doc="Output parseable information on each filter that can then be fed back to the filter as a training set") public Boolean LEARNING = false;
    @Argument(fullName="truth", shortName="truth", doc="Operate on truth set only") public Boolean TRUTH = false;

    private List<Class<? extends IndependentVariantFeature>> featureClasses;
    private List<Class<? extends VariantExclusionCriterion>> exclusionClasses;

    private PrintWriter vwriter;
    private HashMap<String, PrintWriter> ewriters;
    private HashMap<String, PrintWriter> swriters;

    private ArrayList<IndependentVariantFeature> requestedFeatures;
    private ArrayList<VariantExclusionCriterion> requestedExclusions;

    /**
     * Prepare the output file and the list of available features.
     */
    public void initialize() {
        featureClasses = PackageUtils.getClassesImplementingInterface(IndependentVariantFeature.class);
        exclusionClasses = PackageUtils.getClassesImplementingInterface(VariantExclusionCriterion.class);

        if (LIST) {
            out.println("\nAvailable features: " + getAvailableClasses(featureClasses));
            out.println("Available exclusion criteria: " + getAvailableClasses(exclusionClasses) + "\n");
            System.exit(0);
        }

        try {
            vwriter = new PrintWriter(VARIANTS_OUT_HEAD + ".included.geli.calls");
            vwriter.println(AlleleFrequencyEstimate.geliHeaderString());

            requestedFeatures = new ArrayList<IndependentVariantFeature>();
            requestedExclusions = new ArrayList<VariantExclusionCriterion>();

            swriters = new HashMap<String, PrintWriter>();

            // Initialize requested features
            if (FEATURES != null) {
                for (String requestedFeatureString : FEATURES) {
                    String[] requestedFeaturePieces = requestedFeatureString.split(":");
                    String requestedFeatureName = requestedFeaturePieces[0];
                    String requestedFeatureArgs = (requestedFeaturePieces.length == 2) ? requestedFeaturePieces[1] : "";

                    for ( Class featureClass : featureClasses ) {
                        String featureClassName = rationalizeClassName(featureClass);

                        if (requestedFeatureName.equalsIgnoreCase(featureClassName)) {
                            try {
                                IndependentVariantFeature ivf = (IndependentVariantFeature) featureClass.newInstance();
                                ivf.initialize(requestedFeatureArgs);

                                if (LEARNING) {
                                    PrintWriter studyWriter = new PrintWriter(VARIANTS_OUT_HEAD + "." + featureClassName + ".study");
                                    studyWriter.println(ivf.getStudyHeader());

                                    swriters.put(featureClassName, studyWriter);
                                }

                                requestedFeatures.add(ivf);
                            } catch (InstantiationException e) {
                                throw new StingException(String.format("Cannot instantiate feature class '%s': must be concrete class", featureClass.getSimpleName()));
                            } catch (IllegalAccessException e) {
                                throw new StingException(String.format("Cannot instantiate feature class '%s': must have no-arg constructor", featureClass.getSimpleName()));
                            }
                        }
                    }
                }
            }

            // Initialize requested exclusion criteria
            ewriters = new HashMap<String, PrintWriter>();

            if (EXCLUSIONS != null) {
                for (String requestedExclusionString : EXCLUSIONS) {
                    String[] requestedExclusionPieces = requestedExclusionString.split(":");
                    String requestedExclusionName = requestedExclusionPieces[0];
                    String requestedExclusionArgs = (requestedExclusionPieces.length == 2) ? requestedExclusionPieces[1] : "";

                    for ( Class exclusionClass : exclusionClasses ) {
                        String exclusionClassName = rationalizeClassName(exclusionClass);

                        if (requestedExclusionName.equalsIgnoreCase(exclusionClassName)) {
                            try {
                                VariantExclusionCriterion vec = (VariantExclusionCriterion) exclusionClass.newInstance();
                                vec.initialize(requestedExclusionArgs);

                                if (LEARNING) {
                                    PrintWriter studyWriter = new PrintWriter(VARIANTS_OUT_HEAD + "." + exclusionClassName + ".study");
                                    studyWriter.println(vec.getStudyHeader());

                                    swriters.put(exclusionClassName, studyWriter);
                                }

                                requestedExclusions.add(vec);

                                PrintWriter writer = new PrintWriter(VARIANTS_OUT_HEAD + ".excluded." + exclusionClassName + ".geli.calls");
                                writer.println(AlleleFrequencyEstimate.geliHeaderString());

                                ewriters.put(exclusionClassName, writer);
                            } catch (InstantiationException e) {
                                throw new StingException(String.format("Cannot instantiate exclusion class '%s': must be concrete class", exclusionClass.getSimpleName()));
                            } catch (IllegalAccessException e) {
                                throw new StingException(String.format("Cannot instantiate exclusion class '%s': must have no-arg constructor", exclusionClass.getSimpleName()));
                            }
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            throw new StingException(String.format("Could not open file(s) for writing"));
        }
    }

    /**
     * Trim the 'IVF' or 'VEC' off the feature/exclusion name so the user needn't specify that on the command-line.
     *
     * @param featureClass  the feature class whose name we should rationalize
     * @return  the class name, minus 'IVF'
     */
    private String rationalizeClassName(Class featureClass) {
        String featureClassName = featureClass.getSimpleName();
        String newName = featureClassName.replaceFirst("IVF", "");
        newName = newName.replaceFirst("VEC", "");
        return newName;
    }

    /**
     * Returns a comma-separated list of available classes the user may specify at the command-line.
     *
     * @param classes an ArrayList of classes
     * @return String of available classes 
     */
    private <T> String getAvailableClasses(List<Class<? extends T>> classes) {
        String availableString = "";

        for (int classIndex = 0; classIndex < classes.size(); classIndex++) {
            availableString += rationalizeClassName(classes.get(classIndex)) + (classIndex == classes.size() - 1 ? "" : ",");
        }

        return availableString;
    }

    /**
     * Initialize the number of loci processed to zero.
     *
     * @return 0
     */
    public Integer reduceInit() { return 0; }

    /**
     * For each site of interest, rescore the genotype likelihoods by applying the specified feature set.
     *
     * @param tracker  the meta-data tracker
     * @param ref      the reference base
     * @param context  the context for the given locus
     * @return 1 if the locus was successfully processed, 0 if otherwise
     */
    public Integer map(RefMetaDataTracker tracker, char ref, LocusContext context) {
        rodVariants variant = (rodVariants) tracker.lookup("variant", null);
        
        rodGFF hapmapSite = null;

        for ( ReferenceOrderedDatum datum : tracker.getAllRods() ) {
            if ( datum != null && datum instanceof rodGFF ) {
                hapmapSite = (rodGFF) datum;
            }
        }

        // Ignore places where we don't have a variant or where the reference base is ambiguous.
        if (variant != null && (!TRUTH || hapmapSite != null) && BaseUtils.simpleBaseToBaseIndex(ref) != -1) {
            if (VERBOSE) { out.println("Original:\n  " + variant); }

            // Apply features that modify the likelihoods and LOD scores
            for ( IndependentVariantFeature ivf : requestedFeatures ) {
                ivf.compute(ref, context);

                String featureClassName = rationalizeClassName(ivf.getClass());

                double[] weights = ivf.getLikelihoods();

                variant.adjustLikelihoods(weights);

                if (VERBOSE) { out.println(rationalizeClassName(ivf.getClass()) + ":\n  " + variant); }

                if (LEARNING) {
                    PrintWriter swriter = swriters.get(featureClassName);
                    if (swriter != null) {
                        swriter.println(ivf.getStudyInfo());
                    }
                }
            }

            // Apply exclusion tests that accept or reject the variant call
            ArrayList<String> exclusionResults = new ArrayList<String>();

            // we need to provide an alternative context without mapping quality 0 reads
            // for those exclusion criterion that don't want them
            LocusContext Q0freeContext = removeQ0reads(context);

            for ( VariantExclusionCriterion vec : requestedExclusions ) {
                vec.compute(ref, (vec.useZeroQualityReads() ? context : Q0freeContext), variant);

                String exclusionClassName = rationalizeClassName(vec.getClass());

                if (vec.isExcludable()) {
                    exclusionResults.add(exclusionClassName);
                }

                if (LEARNING) {
                    PrintWriter swriter = swriters.get(exclusionClassName);

                    if (swriter != null) {
                        swriter.println(vec.getStudyInfo());
                        swriter.flush();
                    }
                }
            }

            if (exclusionResults.size() > 0) {
                String exclusions = "";
                for (int i = 0; i < exclusionResults.size(); i++) {
                    exclusions += exclusionResults.get(i) + (i == exclusionResults.size() - 1 ? "" : ",");

                    PrintWriter writer = ewriters.get(exclusionResults.get(i));
                    if (writer != null) {
                        writer.println(variant);
                    }
                }
                
                if (VERBOSE) { out.printf("Exclusions: %s\n", exclusions); }
            } else {
                vwriter.println(variant);
            }

            if (VERBOSE) { out.println(); }

            return 1;
        }

        return 0;
    }

    private LocusContext removeQ0reads(LocusContext context) {
        // set up the variables
        List<SAMRecord> reads = context.getReads();
        List<Integer> offsets = context.getOffsets();
        Iterator<SAMRecord> readIter = reads.iterator();
        Iterator<Integer> offsetIter = offsets.iterator();
        ArrayList<SAMRecord> Q0freeReads = new ArrayList<SAMRecord>();
        ArrayList<Integer> Q0freeOffsets = new ArrayList<Integer>();

        // copy over good reads/offsets
        while ( readIter.hasNext() ) {
            SAMRecord read = readIter.next();
            Integer offset = offsetIter.next();
            if ( read.getMappingQuality() > 0 ) {
                Q0freeReads.add(read);
                Q0freeOffsets.add(offset);
            }                       
        }
        
        return new LocusContext(context.getLocation(), Q0freeReads, Q0freeOffsets);    
    }

    /**
     * Increment the number of loci processed.
     *
     * @param value result of the map.
     * @param sum   accumulator for the reduce.
     * @return the new number of loci processed.
     */
    public Integer reduce(Integer value, Integer sum) {
        return sum + value;
    }

    /**
     * Tell the user the number of loci processed and close out the new variants file.
     *
     * @param result  the number of loci seen.
     */
    public void onTraversalDone(Integer result) {
        out.printf("Processed %d loci.\n", result);

        vwriter.close();

        for (PrintWriter ewriter : ewriters.values()) {
            ewriter.close();
        }

        for (PrintWriter swriter : swriters.values()) {
            swriter.close();
        }
    }
}
