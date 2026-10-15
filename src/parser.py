import re 
import src.reader as reader 
import json 
import src.scraper as scraper 
import os 
 
if not os.path.exists("data/subjects.json"): 
    resultsearch = scraper.search() 
    scraper.generate_file(resultsearch) 
 
with open("data/subjects.json","r",encoding="utf-8") as file: 
    subjects:dict = json.load(file) 

KIND_FROM_FILENAME = {
    "qp": "QP", "ms": "MS", "in": "IN", "er": "ER", "gt": "GT", "ci": "CI",
}
LETTER_SESSION = {"m": "February-March", "s": "May-June", "w": "October-November"}
COMPONENT_TYPES = {"QP", "MS", "IN", "CI"}


def blank_metadata():
    return {
        "qualification": None,
        "subject_name": None,
        "subject_code": None,
        "year": None,
        "session": None,
        "paper": None,
        "variant": None,
        "paper_type": None,
    }


def lookup_subject(code):
    """(qualification, name) when the code belongs to exactly one qualification."""
    found = []
    for qualification, table in subjects.items():
        if code and code in table:
            found.append((qualification, table[code]))
    if len(found) == 1:
        return found[0]
    return None, None


def detect_paper_type(text):
    """MS, GT, ER, IN, CI, or None when it still looks like a question paper (or isn't one)."""
    if not text:
        return None
    head = text[:3000]
    # Mark schemes mention the Principal Examiner Report in their boilerplate, so that
    # phrase must not win over MARK SCHEME. Only the UPPERCASE phrase counts: grade
    # thresholds and examiner reports say "mark scheme" in normal sentences.
    if "MARK SCHEME" in text:
        return "MS"
    if re.search(r"grade thresholds", head, re.I):
        return "GT"
    if re.search(r"principal examiner report|examiner report", head, re.I):
        return "ER"
    if re.search(r"confidential instructions", head, re.I):
        return "CI"
    # Inserts: an "INSERT" heading, "This insert contains…", or a barcode ending in -I (*3736339926-I*)
    if (re.search(r"(?m)^\s*INSERT\s*$", head, re.I) or re.search(r"this insert contains", head, re.I)
            or re.search(r"\*\d{10}-I\*", head)):
        return "IN"
    return None


def _fill_series_gaps(metadata, text):
    """Grade thresholds and examiner reports name the syllabus and the series, not one paper."""
    if not text:
        return
    if not metadata.get("qualification"):
        if re.search(r"IGCSE|General Certificate of Secondary Education", text):
            metadata["qualification"] = "IGCSE"
        elif re.search(r"O Level|Ordinary Level", text):
            metadata["qualification"] = "O Level"
        elif re.search(r"Cambridge International AS Level|Advanced Subsidiary Level(?! and)", text):
            metadata["qualification"] = "AS"
        elif re.search(r"AS & A Level|Advanced Subsidiary and Advanced Level|Advanced Subsidiary Level and Advanced Level|International Advanced Level", text):
            metadata["qualification"] = "AS & A Level"
    if not metadata.get("subject_code"):
        # "Syllabus 0606", "Paper 0606/11", "(0606)" or a heading like "Education 0606 Additional
        # Mathematics". Only numbers that are real subject codes count, so years are never picked.
        patterns = [r"Syllabus\s+(\d{4})", r"(?<!\d)(\d{4})/\d{1,2}(?!\d)", r"\((\d{4})\)", r"(?<!\d)(\d{4})(?!\d)"]
        for pattern in patterns:
            match = next((m for m in re.finditer(pattern, text[:1500]) if lookup_subject(m.group(1))[0]), None)
            if match:
                metadata["subject_code"] = match.group(1)
                break
    if not metadata.get("year") or not metadata.get("session"):
        found = re.search(
            r"(February/March|May/June|October/November|November|March|June)\s*(\d{4})", text)
        if found:
            words = found.group(1).lower()
            if not metadata.get("year"):
                metadata["year"] = found.group(2)
            if not metadata.get("session"):
                if words in ("june", "may/june"):
                    metadata["session"] = "May-June"
                elif words in ("february/march", "march"):
                    metadata["session"] = "February-March"
                elif words in ("october/november", "november"):
                    metadata["session"] = "October-November"


def from_filename(filename):
    """Read Cambridge's own file name, e.g. 9709_w23_qp_12.pdf or 0625_s24_gt.pdf."""
    metadata = blank_metadata()
    name = os.path.basename(filename or "")
    match = re.search(
        r"(?i)(?<!\d)(\d{4})[_\-\s]+([msw])(\d{2})[_\-\s]+(qp|ms|in|er|gt|ci)"
        r"(?:[_\-\s]+(\d)(\d))?(?!\d)",
        name,
    )
    if match:
        metadata["subject_code"] = match.group(1)
        metadata["session"] = LETTER_SESSION.get(match.group(2).lower())
        year = int(match.group(3))
        metadata["year"] = str(1900 + year if year >= 90 else 2000 + year)
        metadata["paper_type"] = KIND_FROM_FILENAME[match.group(4).lower()]
        if match.group(5):
            metadata["paper"] = match.group(5)
            metadata["variant"] = match.group(6)
        return metadata
    own = re.search(r"(?i)Paper\s+(\d)\s+v(\d+)\s+(QP|MS|IN|ER|GT|CI)\b", name)
    if own:
        metadata["paper"] = own.group(1)
        metadata["variant"] = own.group(2)
        metadata["paper_type"] = own.group(3).upper()
    return metadata


def fill_subject(metadata):
    code = metadata.get("subject_code")
    if not code:
        return
    found_qual, found_name = lookup_subject(code)
    chosen = metadata.get("qualification")
    # A code that exists in exactly one list beats a loose text guess (9709 is not IGCSE).
    if found_qual and (not chosen or code not in subjects.get(chosen, {})):
        metadata["qualification"] = found_qual
        metadata["subject_name"] = found_name
    elif chosen and code in subjects.get(chosen, {}):
        metadata["subject_name"] = subjects[chosen][code]
    elif not metadata.get("subject_name"):
        metadata["subject_name"] = found_name or f"Subject {code}"


def identify(text, filename=None):
    """Text first, then the file name fills anything the text didn't say."""
    metadata = meta_extract(text or "")
    explicit = detect_paper_type(text or "") is not None
    file_meta = from_filename(filename or "")
    for key, value in file_meta.items():
        if value and not metadata.get(key):
            metadata[key] = value
    if file_meta.get("paper_type") and not explicit:
        metadata["paper_type"] = file_meta["paper_type"]
    if not metadata.get("paper_type") and metadata.get("subject_code") and metadata.get("paper"):
        metadata["paper_type"] = "QP"
    fill_subject(metadata)
    return metadata


def needs_component(metadata):
    """Question papers, mark schemes and inserts belong to one paper. A series file does not."""
    kind = metadata.get("paper_type")
    if kind == "GT":
        return False
    if kind == "ER" and not metadata.get("paper") and not metadata.get("variant"):
        return False
    return True


def missing_fields(metadata):
    required = ["qualification", "subject_code", "year", "session", "paper_type"]
    if needs_component(metadata):
        required += ["paper", "variant"]
    return [field for field in required if not metadata.get(field)]


def complete_manual(fields):
    """Turn the Fix details form into metadata, or (metadata, error message)."""
    metadata = blank_metadata()
    metadata["subject_code"] = (fields.get("subject_code") or "").strip() or None
    metadata["qualification"] = fields.get("qualification") or None
    metadata["year"] = (fields.get("year") or "").strip() or None
    metadata["session"] = fields.get("session") or None
    metadata["paper"] = (fields.get("paper") or "").strip() or None
    metadata["variant"] = (fields.get("variant") or "").strip() or None
    metadata["paper_type"] = fields.get("paper_type") or None
    if metadata["year"] and not re.fullmatch(r"\d{4}", metadata["year"]):
        return metadata, "Year should be 4 digits, like 2024."
    if metadata["paper"] and not re.fullmatch(r"\d", metadata["paper"]):
        return metadata, "Paper should be a single digit, like 4."
    if metadata["variant"] and not re.fullmatch(r"\d", metadata["variant"]):
        return metadata, "Variant should be a single digit, like 2. Use 0 for a paper with no variant."
    code = metadata["subject_code"]
    found = [(qual, table[code]) for qual, table in subjects.items() if code and code in table]
    chosen = metadata["qualification"]
    if chosen and found and not any(qual == chosen for qual, _ in found):
        if len(found) == 1:
            return metadata, f"{code} is {found[0][0]} ({found[0][1]}), not {chosen}."
        return metadata, f"{code} isn't listed under {chosen}."
    fill_subject(metadata)
    missing = missing_fields(metadata)
    if missing:
        labels = {
            "qualification": "qualification", "subject_code": "subject code", "year": "year",
            "session": "session", "paper": "paper", "variant": "variant", "paper_type": "file type",
        }
        return metadata, "Still needed: " + ", ".join(labels.get(field, field) for field in missing) + "."
    return metadata, None


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
 
    # paper_type extraction. Unknown stays unset so a file name can still say what it is.
    metadata["paper_type"] = detect_paper_type(extracted_pdf)

    # parser logic depending on type of paper
    # Inserts use the same footer code as a question paper. Series files do not.
    if metadata["paper_type"] not in ("MS", "GT", "ER"): 
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
 
            # SESSION DECLARATION. A re-saved PDF sometimes turns O (October) into 0.
            session_letter = code_splitted[2].upper().replace("0", "O")
            if session_letter == "O":
                metadata["session"] = "October-November"
            elif session_letter == "M":
                metadata["session"] = "May-June"
            elif session_letter == "F":
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
 
    _fill_series_gaps(metadata, extracted_pdf)
    temp:dict = subjects.get(metadata["qualification"],{}) 
    metadata["subject_name"] = temp.get(metadata["subject_code"]) 

    return metadata
 
if __name__ == "__main__": 
    print("This is a test\n") 
    pdf = input("Please paste in the pdf u want to extract the metadata from:\n") 
    text = reader.read_file(pdf) 
    print(meta_extract(text))