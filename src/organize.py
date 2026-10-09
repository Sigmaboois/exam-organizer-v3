import src.reader as reader 
import src.parser as parser
import src.renamer as renamer
import src.scraper as scraper

def organize_exam(pdf_path,root_path):

    pdf_extracted = reader.read_file(pdf_path)
    metadata = parser.meta_extract(pdf_extracted)
    folder_path = renamer.folder_create(metadata,root_path)
    renamer.rename(metadata,pdf_path,folder_path)

if __name__ == "__main__":
    print("This is a test\n")
    pdf_path = input("Input the path of the file you would like to organize:\n")
    root_path = input("Input the path where the exam should be sorted to:\n")
    organize_exam(pdf_path,root_path)
