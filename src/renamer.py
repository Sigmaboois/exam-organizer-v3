import src.parser as parser
import src.reader as reader 

import os
import shutil

SERIES_FOLDERS = {
    "GT": "Grade thresholds",
    "ER": "Examiner report",
}
SERIES_NAMES = {
    "GT": "Grade thresholds",
    "ER": "Examiner report",
    "IN": "Insert",
    "CI": "Confidential instructions",
}


def _with_component(metadata):
    """A paper folder, unless this is a whole-series file (grade thresholds, or an examiner report)."""
    return bool(metadata.get("paper") and metadata.get("variant") and metadata.get("paper_type") != "GT")


def folder_create(metadata,folder_path):

    # Example: C:\Users\gasse\OneDrive\Documents\School\Exams\Physics\IGCSE\0625\2024\May-June\4\2
    parts = [
        folder_path,
        metadata["subject_name"],
        metadata["qualification"],
        metadata["subject_code"],
        metadata["year"],
        metadata["session"],
    ]
    if _with_component(metadata):
        parts.extend([metadata["paper"], metadata["variant"]])
    else:
        parts.append(SERIES_FOLDERS.get(metadata.get("paper_type"), "Series"))
    folder_path = os.path.join(*parts)

    os.makedirs(folder_path,exist_ok=True)
    return folder_path

def file_name(metadata):
    if _with_component(metadata):
        return (f'{metadata["subject_name"]} - Paper {metadata["paper"]} '
                f'v{metadata["variant"]} {metadata["paper_type"]}.pdf')
    label = SERIES_NAMES.get(metadata.get("paper_type"), metadata.get("paper_type") or "Paper")
    return f'{metadata["subject_name"]} - {label}.pdf'

def rename(metadata,file,folder_path):
    new_name = os.path.join(folder_path, file_name(metadata))

    # Never overwrite an exam that is already sorted
    if os.path.exists(new_name):
        raise FileExistsError(f"An exam is already sorted at: {new_name}")
    os.rename(file,new_name)

def copy(metadata,file,folder_path):
    """Like rename, but the original stays where it is (works across drives too)."""
    new_name = os.path.join(folder_path, file_name(metadata))

    # Never overwrite an exam that is already sorted
    if os.path.exists(new_name):
        raise FileExistsError(f"An exam is already sorted at: {new_name}")
    shutil.copy2(file,new_name)
    return new_name



if __name__ == "__main__":
    print("This is a test\n")
    pdfpath = input("Please input ur test file path:")
    destination = input("Please input the folder to sort it into:")
    file_extracted = reader.read_file(pdfpath)
    metadata_parsed=parser.identify(file_extracted, pdfpath)
    folder_path = folder_create(metadata_parsed,destination)
    print(copy(metadata_parsed,pdfpath,folder_path))
    