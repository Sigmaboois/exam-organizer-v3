import parser
import reader

import os

def folder_create(metadata):
    folder_path = input("Please input the path where the exams will be assorted to:\n")
    # Example: C:\Users\gasse\OneDrive\Documents\School\Exams
    folder_path = os.path.join(
    folder_path,
    metadata["subject_name"],
    metadata["qualification"],
    metadata["subject_code"],
    metadata["year"],
    metadata["session"],
    metadata["paper"],
    metadata["variant"]
    )

    os.makedirs(folder_path,exist_ok=True)
    return folder_path

def rename(metadata,file,folder_path):
    new_name = os.path.join(folder_path,f'{metadata["subject_name"]} - Paper {metadata["paper"]} v{metadata["variant"]} {metadata["paper_type"]}.pdf')
    os.rename(file,new_name)


    
if __name__ == "__main__":
    print("This is a test\n")
    pdfpath = input("Please input ur test file path:")
    file_extracted = reader.read_file(pdfpath)
    metadata_parsed=parser.meta_extract(file_extracted)
    folder_path = folder_create(metadata_parsed)
    rename(metadata_parsed,pdfpath,folder_path)
    