package org.bigtop.bigpetstore.etl;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.pig.ExecType;
import org.apache.pig.PigServer;
import org.bigtop.bigpetstore.util.BigPetStoreConstants;
import org.bigtop.bigpetstore.util.DeveloperTools;
import org.bigtop.bigpetstore.util.NumericalIdUtils;

/**
 * This class operates by ETL'ing the dataset into pig.
 * The pigServer is persisted through the life of the class, so that the
 * intermediate data sets created in the constructor can be reused.
 */
public class PigCSVCleaner  {

    PigServer pigServer;
    
    public PigCSVCleaner(Path inputPath, Path outputPath, ExecType ex, File... scripts)
            throws Exception {

        
        
        FileSystem fs = FileSystem.get(inputPath.toUri(), new Configuration());
        
        if(! fs.exists(inputPath)){
            throw new RuntimeException("INPUT path DOES NOT exist : " + inputPath);
        }

        if(fs.exists(outputPath)){
            throw new RuntimeException("OUTPUT already exists : " + outputPath);
        }
        // run pig in local mode
        pigServer = new PigServer(ex);

        /**
         * First, split the tabs up.
         * 
         * BigPetStore,storeCode_OK,2 yang,jay,Mon Dec 15 23:33:49 EST
         * 1969,69.56,flea collar
         * 
         * ("BigPetStore,storeCode_OK,2",
         * "yang,jay,Mon Dec 15 23:33:49 EST 1969,69.56,flea collar")
         * 
         * BigPetStore,storeCode_AK,1 amanda,fitzgerald,Sat Dec 20 09:44:25 EET
         * 1969,7.5,cat-food
         */
        pigServer.registerQuery("csvdata = LOAD '<i>' AS (ID,DETAILS);"
                .replaceAll("<i>", inputPath.toString()));

        /**
         * Now, we want to split the two tab delimited feidls into uniform
         * fields of comma separated values. To do this, we 1) Internally split
         * the FIRST and SECOND fields by commas "a,b,c" --> (a,b,c) 2) FLATTEN
         * the FIRST and SECOND fields. (d,e) (a,b,c) -> d e a b c
         */
        pigServer
                .registerQuery(
                        "id_details = FOREACH csvdata GENERATE "
                        + "FLATTEN" + "(STRSPLIT(ID,',',3)) AS " +
                        		"(drop, code, transaction) ,"

                        + "FLATTEN" + "(STRSPLIT(DETAILS,',',5)) AS " +
                            "(lname, fname, date, price," +
                            "product:chararray);");

        pigServer.store("id_details", outputPath.toString());
        
        /**
         * Now we run scripts... this is where you can add some 
         * arbitrary analytics.
         * 
         * We add "input" and "output" parameters so that each 
         * script can read them and use them if they want.  
         * 
         * Otherwise, just hardcode your inputs into your pig scripts.
         */
        int i = 0;
        for(File script : scripts) {
            Map<String,String> parameters = new HashMap<String,String>();
            parameters.put("input", 
                    outputPath.toString());
            
            Path dir = outputPath.getParent();
            Path adHocOut=
                    new Path(
                            dir,
                            BigPetStoreConstants.OUTPUTS.pig_ad_hoc_script.name()+(i++));
            System.out.println("Setting default output to " + adHocOut);
            parameters.put("output", adHocOut.toString());
            
            pigServer.registerScript(script.getAbsolutePath(), parameters);
        }
    }

    private static File[] files(String[] args,int startIndex) {
        List<File> files = new ArrayList<File>();
        for(int i = startIndex ; i < args.length ; i++) {
            File f = new File(args[i]);
            if(! f.exists()) {
                throw new RuntimeException("Pig script arg " + i+ " " + f.getAbsolutePath() + " not found. ");
            }
            files.add(f);
        }
        System.out.println(
                "Ad-hoc analytics:"+
                "Added  " + files.size() + " pig scripts to post process.  "+
                "Each one will be given $input and $output arguments.");
        return files.toArray(new File[]{});
    }
    public static void main(final String[] args) throws Exception {
        System.out.println("Starting pig etl " + args.length);

        Configuration c = new Configuration();
        int res = ToolRunner.run(
                c, 
                
                new Tool() {
                    Configuration conf;
                    @Override
                    public void setConf(Configuration conf) {
                        this.conf=conf;
                    }
                    
                    @Override
                    public Configuration getConf() {
                        return this.conf;
                    }
                    
                    @Override
                    public int run(String[] args) throws Exception {
                        DeveloperTools.validate(
                                args, 
                                "generated data directory",
                                "pig output directory");
                        new PigCSVCleaner(
                                new Path(args[0]),
                                new Path(args[1]),
                                ExecType.MAPREDUCE,
                                files(args,2));
                        return 0;
                    }
                }, args);
        System.exit(res);
      }


}
