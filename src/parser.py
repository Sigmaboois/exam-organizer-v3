import re 
import reader 
import json 
import scraper 
import os 
 
if not os.path.exists("data/subjects.json"): 
    resultsearch = scraper.search() 
    scraper.generate_file(resultsearch) 
 
with open("data/subjects.json","r",encoding="utf-8") as file: 
    subjects:dict = json.load(file) 
 
def meta_extract(extracted_pdf): 
 
    # Initialization of metadata dictionary 
    metadata = { 
        "qualification" : None, 
        "subject_name" : None, 
        "subject_code" : None, 
        "year" : None, 
        "session" : None, 
        "paper" : None, 
        "variant" : None, 
        "paper_type" : None 
    } 
 
    # paper_type extraction 
    if "MARK SCHEME" in extracted_pdf: 
        metadata["paper_type"] = "MS" 
    else: 
        metadata["paper_type"] = "QP" 
     
    # parser logic depending on type of paper 
    if metadata["paper_type"] == "QP": 
        # Initialization of code patterns and search object 
        code_pattern = r"\d{4}/\d{2}/\w/\w/\d{2}" 
        codematch = re.search(code_pattern,extracted_pdf) 
 
        # IF TO CHECK IF CODEMATCH FOUND ANYTHING 
        if codematch: 
            code = codematch.group() 
        else: 
            code = None 
 
        # Qualifcation Extraction 
        IGCSE_pattern = r"IGCSE|General Certificate of Secondary Education" 
        O_level_pattern = r"O Level|Ordinary Level" 
        AS_level_pattern = r"Cambridge International AS Level|Advanced Subsidiary Level(?! and)" 
        AS_ALevel_pattern = r"AS & A Level|Advanced Subsidiary and Advanced Level|Advanced Subsidiary Level and Advanced Level|International Advanced Level" 
 
        if re.search(IGCSE_pattern,extracted_pdf): 
            metadata["qualification"] = "IGCSE" 
        elif re.search(O_level_pattern,extracted_pdf): 
            metadata["qualification"] = "O Level" 
        elif re.search(AS_level_pattern,extracted_pdf): 
            metadata["qualification"] = "AS" 
        elif re.search(AS_ALevel_pattern,extracted_pdf): 
            metadata["qualification"] = "AS & A Level"   
 
        if code is not None: 
            # CODE SPLITTING AND DECLARATION OF SUBJECT CODE 
            code_splitted = code.split("/") 
            metadata["subject_code"] = code_splitted[0] 
 
            # PAPER AND VARIANT DECLARATION 
            paper_variant = code_splitted[1] 
            paper = paper_variant[0] 
            variant = paper_variant[1] 
            metadata["paper"] = paper 
            metadata["variant"] = variant 
 
            # SESSION DECLARATION 
            if code_splitted[2] == "O": 
                metadata["session"] = "October-November" 
            elif code_splitted[2] == "M": 
                metadata["session"] = "May-June" 
            elif code_splitted[2] == "F": 
                metadata["session"] = "February-March" 
             
            # YEAR DECLARATION 
            metadata["year"] = "20" + code_splitted[4] 
 
        else: 
            code_pattern = r"(\d{4})/(\d)(\d)" 
            code_search = re.search(code_pattern,extracted_pdf) 
            year_session_patt = r"(February/March|May/June|October/November|March|June)\s*(\d{4})" 
            year_session_search = re.search(year_session_patt,extracted_pdf) 
 
            if code_search: 
                metadata["subject_code"] = code_search.group(1) 
                metadata["paper"] = code_search.group(2) 
                metadata["variant"] = code_search.group(3) 
            if year_session_search: 
                metadata["year"] = year_session_search.group(2) 
                if year_session_search.group(1).lower() in ("june","may/june"): 
                    metadata["session"] = "May-June" 
                elif year_session_search.group(1).lower() in ("february/march","march"): 
                    metadata["session"] = "February-March" 
                elif year_session_search.group(1).lower() == "october/november": 
                    metadata["session"] = "October-November" 
 
    if metadata["paper_type"] == "MS": 
        # MS extraction logic 
 
        new_code_paperv_pattern =r"(\d{4})/(\d{2}) (.+?) – Mark Scheme" 
        new_code_paperv = re.search(new_code_paperv_pattern,extracted_pdf) 
         
        if not new_code_paperv: 
            old_code_paperv_pattern = r"(.+?) – (October/November|May/June|February/March|March|June) (\d{4})\s+(\d{4})\s+(\d{2})" 
            old_code_paperv = re.search(old_code_paperv_pattern,extracted_pdf) 
 
             
            if old_code_paperv: 
                qual_in_old = old_code_paperv.group(1).strip().lower() 
                if "igcse" in qual_in_old: 
                    metadata["qualification"] = "IGCSE" 
                elif "o level" in qual_in_old: 
                    metadata["qualification"] = "O Level" 
                elif "as level" in qual_in_old: 
                    metadata["qualification"] = "AS" 
                elif "a level" in qual_in_old: 
                    metadata["qualification"] = "AS & A Level" 
 
                if old_code_paperv.group(2).strip().lower() in ("march","february","february/march"): 
                    metadata["session"] = "February-March" 
                elif old_code_paperv.group(2).strip().lower() in ("may","june","may/june"): 
                    metadata["session"] = "May-June" 
                elif old_code_paperv.group(2).strip().lower() in ("october","november","october/november"): 
                    metadata["session"] = "October-November" 
                 
                metadata["year"] = old_code_paperv.group(3).strip() 
                metadata["subject_code"] = old_code_paperv.group(4).strip() 
                old_pv = old_code_paperv.group(5) 
                metadata["paper"] = old_pv[0] 
                metadata["variant"] = old_pv[1] 
         
        else: 
 
            qual_in_new = new_code_paperv.group(3).strip().lower() 
            if "igcse" in  qual_in_new: 
                metadata["qualification"] = "IGCSE" 
            elif "o level" in qual_in_new: 
                metadata["qualification"] = "O Level" 
            elif "as level" in qual_in_new: 
                metadata["qualification"] = "AS" 
            elif "a level" in qual_in_new: 
                metadata["qualification"] = "AS & A Level" 
 
             
            metadata["subject_code"] = new_code_paperv.group(1).strip() 
 
            pv = new_code_paperv.group(2) 
            metadata["paper"] = pv[0] 
            metadata["variant"] = pv[1] 
 
            new_year_session_pattern = r"mark schemes for the\s+(May/June|October/November|February/March|March|June)\s+(\d{4})" 
            new_year_session = re.search(new_year_session_pattern,extracted_pdf) 
            if new_year_session: 
                session = new_year_session.group(1) 
                if session == "June": 
                    metadata["session"] = "May-June" 
                elif session == "March": 
                    metadata["session"] = "February-March" 
                else: 
                    metadata["session"] = session.replace("/", "-") 
 
                metadata["year"] = new_year_session.group(2).strip() 
 
    temp:dict = subjects.get(metadata["qualification"],{}) 
    metadata["subject_name"] = temp.get(metadata["subject_code"]) 
 
    return metadata 
 
if __name__ == "__main__": 
    print("This is a test\n") 
    pdf = input("Please paste in the pdf u want to extract the metadata from:\n") 
    text = reader.read_file(pdf) 
    print(meta_extract(text))