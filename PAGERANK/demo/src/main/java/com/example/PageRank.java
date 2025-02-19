package com.example;

import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;

public class PageRank {
	
	// args example = ["/input", "/output", "/input/pagerank_data.txt", "0.85", "5", "0.01", "true", "true"]
	public static void main(String[] args) throws Exception
	{
		if (args.length != 8)
		{
			System.out.println("Invalid arguments, expected 8 (inputpath, outputpath, datapath, df, maxruns, convergence, deleteoutput, showresults).");
			System.exit(1);
		}

		int maxRuns = Integer.parseInt(args[4]);
		float dampingFactor = Float.parseFloat(args[3]);
		float mindiff = Float.parseFloat(args[5]);
		FileSystem fs = FileSystem.get(new Configuration());
			
		// Deleting the output folder if asked/needed
		if (Boolean.parseBoolean(args[6]))
		{
			Path outputPath = new Path(args[1]);
			if (fs.exists(outputPath))
			{
				System.out.println("Deleting /output..");
				fs.delete(outputPath, true);
			}
		}
		
		// Lấy dữ liệu về tổng node (để khởi tạo chuẩn hóa,...)
	    Path inputPath = new Path(args[2]);  // Dữ liệu chứa node & edge
	    BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(inputPath)));
	    String firstLine = br.readLine(); // Đọc dòng đầu tiên
	    br.close();
	    String[] split = firstLine.split(" ");
	    int nodesCount = Integer.parseInt(split[0]);  // Lấy số lượng nodes từ dữ liệu
	    int finalRun = 0; // Thêm khai báo


	    System.out.println("Nodes count: " + nodesCount); // Debug log


		// Step 1
	    boolean success = step1(args[0], args[1] + "/ranks0", nodesCount);
		
		// Step 2
		HashMap<Integer, Float> lastRanks = getRanks(fs, args[1] + "/ranks0");
		for (int i = 0; i < maxRuns; i++) 
		{
			success = success && step2(args[1] + "/ranks" + i, args[1] + "/ranks" + (i + 1), dampingFactor, nodesCount);
			
			// Calculate diff of ranks
			HashMap<Integer, Float> newRanks = getRanks(fs, args[1] + "/ranks" + (i + 1));
			float diff = calculateDiff(lastRanks, newRanks);
			System.out.println("Run #" + (i + 1) + " finished (score diff: " + diff + ").");
			
			finalRun = i + 1; // Cập nhật lần lặp cuối cùng
			
			// If the diff is lower than the mindiff we stop the iterations
			if (diff < mindiff)
				break;
			// Otherwise continue
			else
				lastRanks = newRanks;
		}
		
		// Step 3
		// success = success && step3(args[1] + "/ranks" + maxRuns, args[1] + "/ranking", args[2]);
		success = success && step3(args[1] + "/ranks" + finalRun, args[1] + "/ranking", args[2]);



		
		// Show results if asked
		if (Boolean.parseBoolean(args[7]))
		{
			showResults(fs, args[1] + "/ranking");
		}
		
		System.exit(success ? 0 : 1);
	}
	
	private static boolean step1(String input, String output, int nodesCount) throws Exception 
	{
	    Configuration conf = new Configuration();
	    conf.set("mapreduce.fileoutputcommitter.marksuccessfuljobs", "false");
	    conf.setInt("nodesCount", nodesCount);  // Truyền nodesCount vào Configuration

	    System.out.println("Step 1..");
	    Job job = Job.getInstance(conf, "Step 1");
	    job.setJarByClass(PageRank.class);

	    job.setMapperClass(Step1Mapper.class);
	    job.setMapOutputKeyClass(IntWritable.class);
	    job.setMapOutputValueClass(IntWritable.class);

	    job.setReducerClass(Step1Reducer.class);
	    job.setOutputKeyClass(IntWritable.class);
	    job.setOutputValueClass(Text.class);

	    FileInputFormat.addInputPath(job, new Path(input));
	    FileOutputFormat.setOutputPath(job, new Path(output));

	    return job.waitForCompletion(true);
	}

	
	private static boolean step2(String input, String output, float dampingFactor, int nodesCount) throws Exception
	{
		Configuration conf = new Configuration();
		conf.set("mapreduce.fileoutputcommitter.marksuccessfuljobs", "false");
		conf.setFloat("df", dampingFactor);
	    conf.setInt("nodesCount", nodesCount);  // Truyền nodesCount cho Step2Reducer

		
		System.out.println("Step 2..");
		Job job = Job.getInstance(conf, "Step 2");
		job.setJarByClass(PageRank.class);
		
		job.setMapperClass(Step2Mapper.class);
		job.setMapOutputKeyClass(Text.class);
		job.setMapOutputValueClass(Text.class);
	
		job.setReducerClass(Step2Reducer.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);
		
		FileInputFormat.addInputPath(job, new Path(input));
		FileOutputFormat.setOutputPath(job, new Path(output));
		
		return job.waitForCompletion(true);
	}

	private static boolean step3(String input, String output, String urlsPath) throws Exception
	{
		Configuration conf = new Configuration();
		conf.set("mapreduce.fileoutputcommitter.marksuccessfuljobs", "false");
		conf.set("urls_path", urlsPath);
		
		System.out.println("Step 3..");
		Job job = Job.getInstance(conf, "Step 3");
		job.setJarByClass(PageRank.class);
		job.setSortComparatorClass(SortFloatComparator.class);
		
		job.setMapperClass(Step3Mapper.class);
		job.setMapOutputKeyClass(FloatWritable.class);
		job.setMapOutputValueClass(Text.class);
		
		FileInputFormat.addInputPath(job, new Path(input));
		FileOutputFormat.setOutputPath(job, new Path(output));
		
		return job.waitForCompletion(true);
	}
	
	private static void showResults(FileSystem fs, String dir) throws Exception
	{
		Path path = new Path(dir + "/part-r-00000");
		if (!fs.exists(path))
		{
			System.out.println("The file part-r-00000 doesn't exist.");
			return;
		}
		
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(path)));
		String line;
		while ((line = br.readLine()) != null)
		{
			System.out.println(line);
		}
	}
	
	private static HashMap<Integer, Float> getRanks(FileSystem fs, String dir) throws Exception
	{
		Path path = new Path(dir + "/part-r-00000");
		if (!fs.exists(path))
			throw new Exception("The file part-r-00000 doesn't exist.");
		
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(path)));
		String line;
		HashMap<Integer, Float> ranks = new HashMap<Integer, Float>();
		
		while ((line = br.readLine()) != null)
		{
			String[] split = line.split("\t");
			ranks.put(Integer.parseInt(split[0]), Float.parseFloat(split[1]));
		}
		
		return ranks;
	}
	
	private static float calculateDiff(HashMap<Integer, Float> lastRanks, HashMap<Integer, Float> newRanks)
	{
		float diff = 0;
		
		for (int key : newRanks.keySet())
		{
			float lri = lastRanks.containsKey(key) ? lastRanks.get(key) : 0;
			diff += Math.abs(newRanks.get(key) - lri);
		}
		
		return diff;
	}
	
}