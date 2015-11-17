package org.broadinstitute.dropseqrna.barnyard;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMProgramRecord;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.CollectionUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Interval;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.OverlapDetector;
import htsjdk.samtools.util.ProgressLogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.broadinstitute.dropseqrna.TranscriptomeException;
import org.broadinstitute.dropseqrna.annotation.GeneAnnotationReader;
import org.broadinstitute.dropseqrna.cmdline.DropSeq;
import org.broadinstitute.dropseqrna.utils.FilteredIterator;
import org.broadinstitute.dropseqrna.utils.StringTagComparator;
import org.broadinstitute.dropseqrna.utils.readiterators.SamRecordSortingIteratorFactory;

import picard.analysis.MetricAccumulationLevel;
import picard.analysis.RnaSeqMetrics;
import picard.analysis.directed.RnaSeqMetricsCollector;
import picard.annotation.Gene;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.CommandLineProgramProperties;
import picard.cmdline.Option;
import picard.cmdline.StandardOptionDefinitions;

/**
 * An adaptation of the Picard RnaSeqMetricsCollector to collect per-cell data.  In particular, the exon/intron/genic/intragenic/rRNA levels.
 * @author nemesh
 *
 */

@CommandLineProgramProperties(
        usage = "An adaptation of the Picard RnaSeqMetricsCollector to collect per-cell data.  In particular, the exon/intron/genic/intragenic/rRNA levels" +
        		" This program looks at the mapping from each of the reads in both genomic and library space, and selects the better mapping.",
        usageShort = "Measures the intron/exon/genic/intergenic/rRNA levels of each cell.",
        programGroup = DropSeq.class
)
public class SingleCellRnaSeqMetricsCollector extends CommandLineProgram {

	private static final Log log = Log.getInstance(SingleCellRnaSeqMetricsCollector.class);

	@Option(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME, doc = "The input SAM or BAM file to analyze.")
	public File INPUT;

	@Option(shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc="Output file of per-cell exonic/intronic/genic/intergenic/rRNA levels.  This supports zipped formats like gz and bz2.")
	public File OUTPUT;

	@Option(doc="The cell barcode tag.  If there are no reads with this tag, the program will assume that all reads belong to the same cell and process in single sample mode.")
	public String CELL_BARCODE_TAG="XC";

	@Option(doc="Gene annotations in refFlat or GTF format.")
	public File ANNOTATIONS_FILE;

    @Option(doc="Location of rRNA sequences in genome, in interval_list format.  " +
            "If not specified no bases will be identified as being ribosomal.  " +
            "Format described here: http://picard.sourceforge.net/javadoc/net/sf/picard/util/IntervalList.html", optional = true)
    public File RIBOSOMAL_INTERVALS;

    // for backwards compatability, if the strand isn't set, set it to none.
    // TODO should this default be set to FIRST_READ?
    @Option(shortName = "STRAND", doc="For strand-specific library prep. " +
            "For unpaired reads, use FIRST_READ_TRANSCRIPTION_STRAND if the reads are expected to be on the transcription strand.")
    public RnaSeqMetricsCollector.StrandSpecificity STRAND_SPECIFICITY = RnaSeqMetricsCollector.StrandSpecificity.NONE;

    @Option(doc="This percentage of the length of a fragment must overlap one of the ribosomal intervals for a read or read pair by this must in order to be considered rRNA.")
    public double RRNA_FRAGMENT_PERCENTAGE = 0.8;

    @Option(doc="Number of cells that you think are in the library. The top NUM_CORE_BARCODES will be reported in the output.")
	public Integer NUM_CORE_BARCODES=null;

    @Option(doc="The map quality of the read to be included for determining which cells will be measured.")
	public Integer READ_MQ=10;

    @Override
	protected int doWork() {


    	IOUtil.assertFileIsReadable(INPUT);
		IOUtil.assertFileIsWritable(OUTPUT);
		BarcodeListRetrieval u = new BarcodeListRetrieval();

		//BufferedWriter out = OutputWriterUtil.getWriter(OUTPUT);
		//writeHeader(out);

		Set<String> cellBarcodes = new HashSet<String>(u.getListCellBarcodesByReadCount (this.INPUT, this.CELL_BARCODE_TAG, this.READ_MQ, null, this.NUM_CORE_BARCODES));
		RnaSeqMetricsCollector collector = getRNASeqMetricsCollector(this.CELL_BARCODE_TAG, cellBarcodes, this.INPUT, this.STRAND_SPECIFICITY, this.RRNA_FRAGMENT_PERCENTAGE, this.READ_MQ, this.ANNOTATIONS_FILE, this.RIBOSOMAL_INTERVALS);

		final MetricsFile<RnaSeqMetrics, Integer> file = new MetricsFile<RnaSeqMetrics, Integer>();
    	collector.addAllLevelsToFile(file);

    	BufferedWriter b = IOUtil.openFileForBufferedWriting(OUTPUT);
    	file.write(b);
    	try {
			b.close();
		} catch (IOException io) {
			throw new TranscriptomeException("Problem writing file", io);
		}
    	return 0;
    }

    RnaSeqMetricsCollector getRNASeqMetricsCollector(final String cellBarcodeTag, final Set<String> cellBarcodes, final File inBAM,
    		final RnaSeqMetricsCollector.StrandSpecificity strand, final double rRNAFragmentPCT, final int readMQ,
    		final File annotationsFile, final File rRNAIntervalsFile) {

    	CollectorFactory factory = new CollectorFactory(inBAM, strand, rRNAFragmentPCT, annotationsFile, rRNAIntervalsFile);
		RnaSeqMetricsCollector collector=  factory.getCollector(cellBarcodes);
		List<SAMReadGroupRecord> rg = factory.getReadGroups(cellBarcodes);

        // iterate by cell barcodes.  Skip all the reads without cell barcodes.
		CloseableIterator<SAMRecord> iter = getReadsInTagOrder (inBAM, cellBarcodeTag, rg, cellBarcodes, readMQ);

		while (iter.hasNext()) {
			SAMRecord r = iter.next();
			String cellBarcode = r.getStringAttribute(cellBarcodeTag);
			r.setAttribute("RG", cellBarcode);
	    	collector.acceptRecord(r, null);
		}

		collector.finish();
		return (collector);
    }


    /**
     * Sets up the reads in cell barcode order.
     * Only adds reads that pass the map quality and are in the set of cell barcodes requested.
     *
     * I've tried adapting this to the TagOrderIterator API, but it seems like I need to add the read groups to the header of the temporary BAM that gets
     * iterated on or this doesn't work.
     */
    private CloseableIterator<SAMRecord> getReadsInTagOrder (final File bamFile, final String primaryTag,
                                                             final List<SAMReadGroupRecord> rg,
                                                             final Set<String> allCellBarcodes, final int mapQuality) {

		SamReader reader = SamReaderFactory.makeDefault().open(bamFile);
		SAMSequenceDictionary dict= reader.getFileHeader().getSequenceDictionary();
		List<SAMProgramRecord> programs =reader.getFileHeader().getProgramRecords();

		final SAMFileHeader writerHeader = new SAMFileHeader();
		// reader.getFileHeader().setReadGroups(rg);
		for (SAMReadGroupRecord z: rg) {
			reader.getFileHeader().addReadGroup(z);
			writerHeader.addReadGroup(z);
		}
        writerHeader.setSortOrder(SAMFileHeader.SortOrder.queryname);
        writerHeader.setSequenceDictionary(dict);
        for (SAMProgramRecord spr : programs)
			writerHeader.addProgramRecord(spr);

        // This not only filters, but sets the RG attribute on reads it allows through.
        final FilteredIterator<SAMRecord> rgAddingFilter = new FilteredIterator<SAMRecord>(reader.iterator()) {
            @Override
            protected boolean filterOut(final SAMRecord r) {
                String cellBarcode = r.getStringAttribute(primaryTag);
                if (allCellBarcodes.contains(cellBarcode) & r.getMappingQuality() >= mapQuality) {
                    r.setAttribute("RG", cellBarcode);
                    return false;
                } else
					return true;
            }
        };

        ProgressLogger p = new ProgressLogger(log, 1000000, "Preparing reads in core barcodes");
        CloseableIterator<SAMRecord> sortedIterator = SamRecordSortingIteratorFactory.create(writerHeader, rgAddingFilter, new StringTagComparator(primaryTag), p);


		log.info("Sorting finished.");
		return (sortedIterator);
	}

    private class CollectorFactory {
    	final OverlapDetector<Gene> geneOverlapDetector;
    	final Long ribosomalBasesInitialValue;
    	final OverlapDetector<Interval> ribosomalSequenceOverlapDetector;
    	final HashSet<Integer> ignoredSequenceIndices;
    	final RnaSeqMetricsCollector.StrandSpecificity specificity;
    	final double rnaFragPct;

    	public CollectorFactory (final File bamFile, final RnaSeqMetricsCollector.StrandSpecificity specificity, final double rnaFragPct, final File annotationsFile, final File ribosomalIntervals) {
    		this.specificity=specificity;
    		this.rnaFragPct=rnaFragPct;
    		SamReader reader = SamReaderFactory.makeDefault().open(bamFile);
    		geneOverlapDetector = GeneAnnotationReader.loadAnnotationsFile(annotationsFile, reader.getFileHeader().getSequenceDictionary());
            log.info("Loaded " + geneOverlapDetector.getAll().size() + " genes.");
            ribosomalBasesInitialValue = ribosomalIntervals != null ? 0L : null;
            ribosomalSequenceOverlapDetector = RnaSeqMetricsCollector.makeOverlapDetector(bamFile, reader.getFileHeader(), ribosomalIntervals);
            ignoredSequenceIndices = RnaSeqMetricsCollector.makeIgnoredSequenceIndicesSet(reader.getFileHeader(), new HashSet<String>());
            CloserUtil.close(reader);
    	}

    	public RnaSeqMetricsCollector getCollector(final Set<String> cellBarcodes) {
    		List<SAMReadGroupRecord> readGroups =  getReadGroups(cellBarcodes);
    		return new RnaSeqMetricsCollector(CollectionUtil.makeSet(MetricAccumulationLevel.READ_GROUP), readGroups,
                    ribosomalBasesInitialValue, geneOverlapDetector, ribosomalSequenceOverlapDetector,
                    ignoredSequenceIndices, 500, specificity, this.rnaFragPct, false);
    	}

    	public List<SAMReadGroupRecord> getReadGroups(final Set<String> cellBarcodes) {
    		List<SAMReadGroupRecord> g = new ArrayList<SAMReadGroupRecord>(cellBarcodes.size());
    		for (String id: cellBarcodes) {
    			SAMReadGroupRecord rg = new SAMReadGroupRecord(id);
    			rg.setLibrary(id);
    		    rg.setPlatform(id);
    		    rg.setSample(id);
    		    rg.setPlatformUnit(id);
    			g.add(rg);
    		}
    		return (g);


    	}
    }

    /** Stock main method. */
	public static void main(final String[] args) {
		System.exit(new SingleCellRnaSeqMetricsCollector().instanceMain(args));
	}
}
