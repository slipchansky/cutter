package com.stas;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class CutParameters {

   @Data
   public static class Replacement {
     private String sample = "";
     private String replacement = "";
   };
   
   public static class Replacements extends ArrayList<Replacement> {}; 


   private String input;
   private String output;
   private Replacements replace = new Replacements();
   private List<String> methods = new ArrayList<> ();

}
