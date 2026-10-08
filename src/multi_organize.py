import src.organize as organize

def multi_organize():
    pdf_paths = []
    while True:
        pdf_path = input("Please input the path of the file you would like to organize:\n")
        if pdf_path == "":
            break
        pdf_paths.append(pdf_path)
    
    root_path = input("Input the path where the exams should be sorted to:\n")
    for pdf_path in pdf_paths:
        organize.organize_exam(pdf_path,root_path)
if __name__ == "__main__":
    print("This is a test\n")
    multi_organize()