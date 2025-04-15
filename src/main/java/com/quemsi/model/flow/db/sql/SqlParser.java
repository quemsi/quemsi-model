package com.quemsi.model.flow.db.sql;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Scanner;

import org.apache.commons.io.IOUtils;

import com.quemsi.commons.util.StringUtils;


public class SqlParser {
    public LinkedList<SqlToken> split(String script){
        return split(IOUtils.toInputStream(script, StandardCharsets.UTF_8));
    }
    
    public LinkedList<SqlToken> split(InputStream is){
        LinkedList<SqlToken> tokens = new LinkedList<>();
        Scanner s = new Scanner(is);
        StringBuilder sb = new StringBuilder();
        while(s.hasNextLine()){
            String line = StringUtils.trim(s.nextLine());
            if(!StringUtils.isEmptyOrNull(line)){
                if(line.charAt(line.length() -1) == ';' || (line.length()>=2 && "*/".equals(line.substring(line.length() -2)))){
                    if(sb.length() > 0){
                        sb.append(System.lineSeparator());
                    }
                    sb.append(line);
                    tokens.addLast(new SqlToken(sb.toString()));
                    sb = new StringBuilder();
                } else {
                    if(sb.length() > 0){
                        sb.append(System.lineSeparator());
                    }
                    sb.append(line);
                }
            }
        }
        s.close();
        return tokens;
    }
}
